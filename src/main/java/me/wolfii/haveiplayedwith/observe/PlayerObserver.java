package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import me.wolfii.haveiplayedwith.chat.RenameMessages;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
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
 * Watches the tab list and nearby players every tick so joins and name changes are noticed
 * quickly. Each UUID is only queued once per calendar minute, stored on that UUID rather
 * than wiped globally, so a minute change does not re-offer everyone at once.
 *
 * <p>The client thread only reads game profiles and hands them to {@link #sightings}; every cache
 * read, API call and database write happens on one of this class' own threads.
 */
public final class PlayerObserver {
    /** Sightings waiting on a Mojang lookup, per the 500 entry buffer the API budget allows. */
    private static final int MAX_LOOKUP_BUFFER = 500;
    /** Sightings waiting to be classified as "needs a lookup" or "already known". */
    private static final int MAX_SIGHTING_BUFFER = 2048;
    /**
     * New UUIDs accepted per tick. Spreads a busy tab list across ticks instead of dumping
     * every newly-due player into the queue on the first tick of a minute.
     */
    private static final int NEW_SIGHTINGS_PER_TICK = 16;
    private static final long CREDIT_MEMORY_MINUTES = 60;
    private final PlayerStore players;
    private final MojangProfileApi mojang;
    private final ExecutorService dispatcher = ModThreads.singleWorker("sightings");
    private final ExecutorService lookupWorker = ModThreads.singleWorker("mojang");
    private final BlockingQueue<Sighting> sightings = new ArrayBlockingQueue<>(MAX_SIGHTING_BUFFER);
    private final ConcurrentHashMap<UUID, Sighting> pendingLookups = new ConcurrentHashMap<>();
    private final BlockingQueue<UUID> lookups = new ArrayBlockingQueue<>(MAX_LOOKUP_BUFFER);
    private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();
    /** Calendar minute this UUID was last handed to {@link #sightings}. */
    private final ConcurrentHashMap<UUID, Long> lastNotedMinute = new ConcurrentHashMap<>();
    private final TickPass pass = new TickPass();
    private volatile long currentMinute = Long.MIN_VALUE;
    /** One id for this client run, assigned at boot and kept across join/disconnect. */
    private final String sessionId = UUID.randomUUID().toString();
    private volatile String locationId;
    public PlayerObserver(PlayerStore players, MojangProfileApi mojang) {
        this.players = players;
        this.mojang = mojang;
        dispatcher.execute(this::dispatchLoop);
        lookupWorker.execute(this::lookupLoop);
    }

    public String liveSessionId() {
        return "live:" + sessionId;
    }

    public void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> locationId = PlayLocations.current(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            locationId = null;
            creditedMinute.clear();
            lastNotedMinute.clear();
            currentMinute = Long.MIN_VALUE;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public void close() {
        dispatcher.shutdownNow();
        lookupWorker.shutdownNow();
    }

    private void tick(Minecraft client) {
        if (client.level == null || client.player == null || client.getConnection() == null) {
            return;
        }
        long epochMinute = System.currentTimeMillis() / 60_000L;
        pruneIfNewMinute(epochMinute);
        pass.reset(epochMinute, sessionId, location(client));
        ClientPacketListener connection = client.getConnection();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (!offer(info.getProfile(), pass)) {
                return;
            }
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (!offer(player.getGameProfile(), pass)) {
                return;
            }
        }
        offer(client.player.getGameProfile(), pass);
    }

    private void pruneIfNewMinute(long epochMinute) {
        if (epochMinute == currentMinute) {
            return;
        }
        currentMinute = epochMinute;
        long keepAfter = epochMinute - CREDIT_MEMORY_MINUTES;
        lastNotedMinute.values().removeIf(minute -> minute < keepAfter);
        creditedMinute.values().removeIf(minute -> minute < keepAfter);
    }

    private String location(Minecraft client) {
        String current = locationId;
        if (current != null) {
            return current;
        }
        String resolved = PlayLocations.current(client);
        if (resolved != null) {
            locationId = resolved;
        }
        return resolved;
    }

    /**
     * @return {@code false} when this tick should stop offering, because the per-tick budget
     *     is spent or the sighting queue is full
     */
    private boolean offer(GameProfile profile, TickPass pass) {
        if (profile == null) {
            return true;
        }
        UUID uuid = profile.id();
        if (uuid == null) {
            return true;
        }
        Long lastNoted = lastNotedMinute.get(uuid);
        if (lastNoted != null && lastNoted == pass.epochMinute) {
            return true;
        }
        String name = profile.name();
        if (!MinecraftUsernames.isValid(name)) {
            return true;
        }
        LocalDate day = pass.day();
        lastNotedMinute.put(uuid, pass.epochMinute);
        if (!sightings.offer(new Sighting(uuid, name, day, pass.epochMinute, pass.session, pass.serverId))) {
            lastNotedMinute.remove(uuid, pass.epochMinute);
            return false;
        }
        return --pass.budget > 0;
    }

    /** Decides off the client thread whether a sighting can be credited from cache or needs the API. */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Sighting sighting = sightings.take();
                if (mojang.matchesCachedName(sighting.uuid(), sighting.username())) {
                    credit(sighting);
                    continue;
                }
                if (!mojang.needsFetch(sighting.uuid(), sighting.username())) {
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
                var profile = mojang.lookupUuid(uuid);
                if (profile.isEmpty() || !profile.get().username().equalsIgnoreCase(sighting.username())) {
                    continue;
                }
                credit(new Sighting(uuid, profile.get().username(), sighting.day(), sighting.epochMinute(), sighting.sessionId(), sighting.serverId()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                ModLog.LOGGER.debug("Mojang verification failed", e);
            }
        }
    }

    private void credit(Sighting sighting) {
        Long last = creditedMinute.put(sighting.uuid(), sighting.epochMinute());
        if (last != null && last == sighting.epochMinute()) {
            announceRename(sighting.uuid(), players.applyMojangUsername(sighting.uuid(), sighting.username(), Instant.now()), sighting.username());
            return;
        }
        announceRename(
            sighting.uuid(),
            players.recordLivePlay(sighting.uuid(), sighting.username(), sighting.day(), liveSessionId(), sighting.serverId()),
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

    private static final class TickPass {
        private long epochMinute;
        private String session;
        private String serverId;
        private int budget;
        private LocalDate day;

        private void reset(long epochMinute, String session, String serverId) {
            this.epochMinute = epochMinute;
            this.session = session;
            this.serverId = serverId;
            this.budget = NEW_SIGHTINGS_PER_TICK;
            this.day = null;
        }

        private LocalDate day() {
            if (day == null) {
                day = LocalDate.now();
            }
            return day;
        }
    }

    private record Sighting(UUID uuid, String username, LocalDate day, long epochMinute, String sessionId, String serverId) {
    }
}
