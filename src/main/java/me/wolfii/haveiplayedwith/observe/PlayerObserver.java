package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import me.wolfii.haveiplayedwith.chat.RenameMessages;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Watches the tab list and loaded players every tick so joins and name changes are noticed
 * quickly. Each UUID is only queued once per calendar minute, stored on that UUID rather
 * than wiped globally, so a minute change does not re-offer everyone at once.
 *
 * <p>Every newly due player is handed off on the tick it is seen. A cached name is credited
 * immediately. Players that still need a profile lookup stay on a bounded queue. Row writes
 * are paced by the store, so a full tab list does not hit the disk at once.
 *
 * <p>{@link AfkObserver} and {@link NearbyPlayerObserver} tick on their own and write only their own
 * counters, through {@link ActivityWriteQueue}. A player can therefore be written up to three times in one minute.
 *
 * <p>The client thread only reads game profiles and hands them to {@link #sightings}; every cache
 * read, API call and play-time write happens on one of this class' own threads.
 */
public final class PlayerObserver extends PlayerMinuteObserver {
    /** Sightings waiting on a profile lookup, per the 500 entry buffer the API budget allows. */
    private static final int MAX_LOOKUP_BUFFER = 500;
    /** Sightings waiting to be classified as "needs a lookup" or "already known". */
    private static final int MAX_SIGHTING_BUFFER = 2048;
    private final PlayerStore playerStore;
    private final ActivityWriteQueue activityWrites = new ActivityWriteQueue();
    private final ProfileApi profiles;
    private final ExecutorService dispatcher = ModThreads.singleWorker("sightings");
    private final ExecutorService lookupWorker = ModThreads.singleWorker("profiles");
    private final BlockingQueue<Sighting> sightings = new ArrayBlockingQueue<>(MAX_SIGHTING_BUFFER);
    private final ConcurrentHashMap<UUID, Sighting> pendingLookups = new ConcurrentHashMap<>();
    private final BlockingQueue<UUID> lookups = new ArrayBlockingQueue<>(MAX_LOOKUP_BUFFER);
    private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();
    private final AfkObserver afkObserver;
    private final NearbyPlayerObserver nearbyPlayerObserver;
    /** One id for this client run, assigned at boot and kept across join/disconnect. */
    private final String sessionId = UUID.randomUUID().toString();
    private volatile String currentServerId;

    public PlayerObserver(PlayerStore players, ProfileApi profiles) {
        this.playerStore = players;
        this.profiles = profiles;
        this.afkObserver = new AfkObserver(players, activityWrites);
        this.nearbyPlayerObserver = new NearbyPlayerObserver(players, activityWrites);
        dispatcher.execute(this::dispatchLoop);
        lookupWorker.execute(this::lookupLoop);
    }

    public String liveSessionId() {
        return "live:" + sessionId;
    }

    public void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> currentServerId = ServerId.current(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            clear();
            afkObserver.clear();
            nearbyPlayerObserver.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
    }

    @Override
    void clear() {
        super.clear();
        creditedMinute.clear();
        currentServerId = null;
    }

    public void close() {
        dispatcher.shutdownNow();
        lookupWorker.shutdownNow();
        activityWrites.close();
    }

    private void clientTick(Minecraft client) {
        tick(client, System.currentTimeMillis() / 60_000L);
    }

    @Override
    void tick(Minecraft client, long epochMinute) {
        if (client.level == null || client.player == null || client.getConnection() == null) {
            return;
        }
        if (prune(epochMinute)) {
            long keepAfter = epochMinute - MEMORY_MINUTES;
            creditedMinute.values().removeIf(minute -> minute < keepAfter);
        }
        String serverId = resolveServerId(client);
        if (serverId == null) {
            return;
        }
        afkObserver.tick(client, epochMinute);
        nearbyPlayerObserver.tick(client, epochMinute);
        LocalDate day = LocalDate.now();
        observeAllPlayers(client, epochMinute, profile -> sightings.offer(
            new Sighting(profile.id(), profile.name(), day, epochMinute, serverId)
        ));
    }

    private String resolveServerId(Minecraft client) {
        String current = currentServerId;
        if (current != null) {
            return current;
        }
        String resolved = ServerId.current(client);
        if (resolved != null) {
            currentServerId = resolved;
        }
        return resolved;
    }

    /**
     * Decides off the client thread whether a sighting can be credited from cache or needs the API.
     * A cached UUID↔name match is credited with no request, even when that cache row is old.
     */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Sighting sighting = sightings.take();
                if (profiles.matchesCachedName(sighting.uuid(), sighting.username())) {
                    credit(sighting);
                    continue;
                }
                if (!profiles.needsFetch(sighting.uuid(), sighting.username())) {
                    continue;
                }
                Sighting previous = pendingLookups.put(sighting.uuid(), sighting);
                if (previous == null && !lookups.offer(sighting.uuid())) {
                    pendingLookups.remove(sighting.uuid(), sighting);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                ModLog.LOGGER.debug("Player observation failed", e);
            }
        }
    }

    private void lookupLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                UUID uuid = lookups.take();
                Sighting sighting = pendingLookups.remove(uuid);
                if (sighting == null) {
                    continue;
                }
                var profile = profiles.lookupUuid(uuid);
                if (profile.isEmpty() || !profile.get().username().equalsIgnoreCase(sighting.username())) {
                    continue;
                }
                credit(new Sighting(uuid, profile.get().username(), sighting.day(), sighting.epochMinute(), sighting.serverId()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                ModLog.LOGGER.debug("Profile verification failed", e);
            }
        }
    }

    private void credit(Sighting sighting) {
        Long last = creditedMinute.put(sighting.uuid(), sighting.epochMinute());
        if (last != null && last == sighting.epochMinute()) {
            announceRename(sighting.uuid(), playerStore.applyUsername(sighting.uuid(), sighting.username(), Instant.now()), sighting.username());
            return;
        }
        announceRename(
            sighting.uuid(),
            playerStore.recordLivePlay(
                sighting.uuid(),
                sighting.username(),
                sighting.day(),
                liveSessionId(),
                sighting.serverId()
            ),
            sighting.username()
        );
    }

    private void announceRename(UUID uuid, Optional<String> previousName, String currentName) {
        if (previousName.isEmpty()) {
            return;
        }
        String previous = previousName.get();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            LocalPlayer self = client.player;
            if (self == null || uuid.equals(self.getUUID())) {
                return;
            }
            Component message = RenameMessages.playerRenamed(previous, currentName, uuid);
            self.sendSystemMessage(message);
        });
    }

    private record Sighting(UUID uuid, String username, LocalDate day, long epochMinute, String serverId) {
    }
}
