package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import me.wolfii.haveiplayedwith.chat.RenameMessages;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
 * <p>The minute is sampled once, when that player is first seen. It counts as AFK when
 * {@link FramerateLimitTracker}'s last input is older than Minecraft's AFK threshold, and as
 * nearby when that player is within {@link Proximity#NEARBY_BLOCKS} blocks on that tick.
 * Both are subsets of the play minute; going AFK or walking into range later does not change it.
 *
 * <p>The client thread only reads game profiles and hands them to {@link #sightings}; every cache
 * read, API call and database write happens on one of this class' own threads.
 */
public final class PlayerObserver {
    /** Sightings waiting on a profile lookup, per the 500 entry buffer the API budget allows. */
    private static final int MAX_LOOKUP_BUFFER = 500;
    /** Sightings waiting to be classified as "needs a lookup" or "already known". */
    private static final int MAX_SIGHTING_BUFFER = 2048;
    private static final long CREDIT_MEMORY_MINUTES = 60;
    private final PlayerStore players;
    private final ProfileApi profiles;
    private final ExecutorService dispatcher = ModThreads.singleWorker("sightings");
    private final ExecutorService lookupWorker = ModThreads.singleWorker("profiles");
    private final BlockingQueue<Sighting> sightings = new ArrayBlockingQueue<>(MAX_SIGHTING_BUFFER);
    private final ConcurrentHashMap<UUID, Sighting> pendingLookups = new ConcurrentHashMap<>();
    private final BlockingQueue<UUID> lookups = new ArrayBlockingQueue<>(MAX_LOOKUP_BUFFER);
    /** Calendar minute this UUID was last handed to {@link #sightings}. */
    private final ConcurrentHashMap<UUID, Long> lastNotedMinute = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();
    private final Set<UUID> nearbyNow = new HashSet<>();
    private final TickPass pass = new TickPass();
    private volatile long currentMinute = Long.MIN_VALUE;
    /** One id for this client run, assigned at boot and kept across join/disconnect. */
    private final String sessionId = UUID.randomUUID().toString();
    private volatile String currentServerId;
    public PlayerObserver(PlayerStore players, ProfileApi profiles) {
        this.players = players;
        this.profiles = profiles;
        dispatcher.execute(this::dispatchLoop);
        lookupWorker.execute(this::lookupLoop);
    }

    public String liveSessionId() {
        return "live:" + sessionId;
    }

    public void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> currentServerId = ServerId.current(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            currentServerId = null;
            lastNotedMinute.clear();
            creditedMinute.clear();
            nearbyNow.clear();
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
        markNearby(client.player, client.level.players());
        String serverId = resolveServerId(client);
        if (serverId == null) {
            return;
        }
        pass.reset(epochMinute, serverId, clientAfk(client));
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

    /** Same threshold {@link FramerateLimitTracker} uses before it throttles the framerate. */
    private static boolean clientAfk(Minecraft client) {
        FramerateLimitTracker tracker = client.getFramerateLimitTracker();
        return Util.getMillis() - tracker.latestInputTime > FramerateLimitTracker.AFK_THRESHOLD_MS;
    }

    private void markNearby(LocalPlayer self, Iterable<AbstractClientPlayer> others) {
        nearbyNow.clear();
        for (AbstractClientPlayer other : others) {
            if (other == null || other == self) {
                continue;
            }
            UUID uuid = other.getUUID();
            if (uuid == null || uuid.equals(self.getUUID())) {
                continue;
            }
            if (Proximity.nearby(other.getX() - self.getX(), other.getY() - self.getY(), other.getZ() - self.getZ())) {
                nearbyNow.add(uuid);
            }
        }
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
     * @return {@code false} when the sighting queue is full and this tick should stop offering
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
        if (!sightings.offer(new Sighting(uuid, name, day, pass.epochMinute, pass.serverId, pass.clientAfk, nearbyNow.contains(uuid)))) {
            lastNotedMinute.remove(uuid, pass.epochMinute);
            return false;
        }
        return true;
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
                credit(new Sighting(
                    uuid,
                    profile.get().username(),
                    sighting.day(),
                    sighting.epochMinute(),
                    sighting.serverId(),
                    sighting.clientAfk(),
                    sighting.nearby()
                ));
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
            announceRename(sighting.uuid(), players.applyUsername(sighting.uuid(), sighting.username(), Instant.now()), sighting.username());
            return;
        }
        announceRename(
            sighting.uuid(),
            players.recordLivePlay(
                sighting.uuid(),
                sighting.username(),
                sighting.day(),
                liveSessionId(),
                sighting.serverId(),
                sighting.clientAfk(),
                sighting.nearby()
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

    private static final class TickPass {
        private long epochMinute;
        private String serverId;
        private LocalDate day;
        private boolean clientAfk;

        private void reset(long epochMinute, String serverId, boolean clientAfk) {
            this.epochMinute = epochMinute;
            this.serverId = serverId;
            this.day = null;
            this.clientAfk = clientAfk;
        }

        private LocalDate day() {
            if (day == null) {
                day = LocalDate.now();
            }
            return day;
        }
    }

    private record Sighting(
        UUID uuid,
        String username,
        LocalDate day,
        long epochMinute,
        String serverId,
        boolean clientAfk,
        boolean nearby
    ) {
    }
}
