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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Coordinates player, AFK, and proximity observations, their background work, and lifecycle. */
public final class ObservationManager implements AutoCloseable {
    private static final int MAX_ACTIVITY_WRITES = 2048;
    /** Sightings waiting on a profile lookup within the API budget. */
    private static final int MAX_LOOKUP_BUFFER = 500;
    /** Ticks whose new sightings are waiting to be classified. */
    private static final int MAX_SIGHTING_BATCHES = 8;
    private final PlayerStore playerStore;
    private final ProfileApi profiles;
    private final BlockingQueue<List<Sighting>> sightings = new ArrayBlockingQueue<>(MAX_SIGHTING_BATCHES);
    private final ConcurrentHashMap<UUID, Sighting> pendingLookups = new ConcurrentHashMap<>();
    private final BlockingQueue<UUID> lookups = new ArrayBlockingQueue<>(MAX_LOOKUP_BUFFER);
    private final ConcurrentHashMap<UUID, Long> creditedMinutes = new ConcurrentHashMap<>();
    private long currentMinute = Long.MIN_VALUE;
    private final ExecutorService sightingWorker = ModThreads.singleWorker("sightings");
    private final ExecutorService profileWorker = ModThreads.singleWorker("profiles");
    private final ExecutorService activityWorker = ModThreads.singleWorker("activity-writes");
    private final BlockingQueue<Runnable> activityWrites = new ArrayBlockingQueue<>(MAX_ACTIVITY_WRITES);
    private final String sessionId = "live:" + UUID.randomUUID();
    private final List<PlayerMinuteObserver> observers;
    private String currentServerId;

    public ObservationManager(PlayerStore players, ProfileApi profiles) {
        this.playerStore = players;
        this.profiles = profiles;
        observers = List.of(
            new AfkObserver(uuid -> offerActivityWrite(() -> players.recordAfkMinute(uuid))),
            new NearbyPlayerObserver(uuid -> offerActivityWrite(() -> players.recordNearbyMinute(uuid))),
            new PlayerObserver(this::offerSightings)
        );
        sightingWorker.execute(this::dispatchLoop);
        profileWorker.execute(this::lookupLoop);
        activityWorker.execute(this::drainActivityWrites);
    }

    public String liveSessionId() {
        return sessionId;
    }

    public void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> currentServerId = ServerId.current(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            observers.forEach(PlayerMinuteObserver::clear);
            creditedMinutes.clear();
            currentMinute = Long.MIN_VALUE;
            currentServerId = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (client.level == null || client.player == null || client.getConnection() == null) {
            return;
        }
        if (currentServerId == null) {
            currentServerId = ServerId.current(client);
        }
        if (currentServerId == null) {
            return;
        }
        long epochMinute = System.currentTimeMillis() / 60_000L;
        if (epochMinute != currentMinute) {
            currentMinute = epochMinute;
            creditedMinutes.values().removeIf(minute -> minute < epochMinute - PlayerMinuteObserver.MEMORY_MINUTES);
        }
        for (PlayerMinuteObserver observer : observers) {
            observer.tick(client, epochMinute, currentServerId);
        }
    }

    boolean offerSightings(List<Sighting> batch) {
        return !sightingWorker.isShutdown() && sightings.offer(List.copyOf(batch));
    }

    /** A refused write leaves the observer free to retry on the next tick. */
    synchronized boolean offerActivityWrite(Runnable write) {
        return !activityWorker.isShutdown() && activityWrites.offer(write);
    }

    @Override
    public void close() {
        sightingWorker.shutdownNow();
        profileWorker.shutdownNow();
        synchronized (this) {
            activityWorker.shutdown();
        }
        try {
            if (!activityWorker.awaitTermination(15, TimeUnit.SECONDS)) {
                ModLog.LOGGER.warn("Timed out draining activity minute writes");
                activityWorker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            activityWorker.shutdownNow();
        }
    }

    private void drainActivityWrites() {
        while (!Thread.currentThread().isInterrupted() && (!activityWorker.isShutdown() || !activityWrites.isEmpty())) {
            try {
                Runnable write = activityWrites.poll(100, TimeUnit.MILLISECONDS);
                if (write != null) {
                    write.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                ModLog.LOGGER.debug("Activity minute write failed", e);
            }
        }
    }

    /**
     * Decides off the client thread whether a sighting can be credited from cache or needs the API.
     * A cached UUID↔name match is credited with no request, even when that cache row is old.
     */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (Sighting sighting : sightings.take()) {
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
        Long last = creditedMinutes.put(sighting.uuid(), sighting.epochMinute());
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
                sessionId,
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

    record Sighting(UUID uuid, String username, LocalDate day, long epochMinute, String serverId) {
    }
}
