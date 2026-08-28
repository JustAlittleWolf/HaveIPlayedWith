package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Watches the tab list and nearby players every tick so joins and name changes are noticed
 * immediately. Each player is only processed once per calendar minute (cached by UUID + name).
 *
 * <p>The client thread only reads game profiles and hands them to {@link #sightings}; every cache
 * read, API call and database write happens on one of this class' own threads.
 */
public final class PlayerObserver {
    private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
    /** Sightings waiting on a Mojang lookup, per the 250 entry buffer the API budget allows. */
    private static final int MAX_LOOKUP_BUFFER = 250;
    /** Sightings waiting to be classified as "needs a lookup" or "already known". */
    private static final int MAX_SIGHTING_BUFFER = 2048;
    private static final long CREDIT_MEMORY_MINUTES = 60;
    private final PlayerStore players;
    private final MojangProfileApi mojang;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(named("haveiplayedwith-sightings"));
    private final ExecutorService lookupWorker = Executors.newSingleThreadExecutor(named("haveiplayedwith-mojang"));
    private final BlockingQueue<Sighting> sightings = new ArrayBlockingQueue<>(MAX_SIGHTING_BUFFER);
    private final ConcurrentHashMap<UUID, Sighting> pendingLookups = new ConcurrentHashMap<>();
    private final BlockingQueue<UUID> lookups = new ArrayBlockingQueue<>(MAX_LOOKUP_BUFFER);
    private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> noticedThisMinute = new ConcurrentHashMap<>();
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

    private static ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> locationId = PlayLocations.current(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            locationId = null;
            creditedMinute.clear();
            noticedThisMinute.clear();
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
        if (epochMinute != currentMinute) {
            currentMinute = epochMinute;
            noticedThisMinute.clear();
            creditedMinute.values().removeIf(minute -> minute < epochMinute - CREDIT_MEMORY_MINUTES);
        }
        String serverId = location(client);
        LocalDate day = LocalDate.now();
        Set<UUID> seen = new HashSet<>();
        ClientPacketListener connection = client.getConnection();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            offer(info.getProfile(), day, epochMinute, sessionId, serverId, seen);
        }
        for (AbstractClientPlayer player : client.level.players()) {
            offer(player.getGameProfile(), day, epochMinute, sessionId, serverId, seen);
        }
        LocalPlayer self = client.player;
        offer(self.getGameProfile(), day, epochMinute, sessionId, serverId, seen);
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

    private void offer(GameProfile profile, LocalDate day, long epochMinute, String session, String serverId, Set<UUID> seen) {
        if (profile == null) {
            return;
        }
        UUID uuid = profile.id();
        String name = profile.name();
        if (uuid == null || !MinecraftUsernames.isValid(name)) {
            return;
        }
        if (!seen.add(uuid)) {
            return;
        }
        if (name.equals(noticedThisMinute.get(uuid))) {
            return;
        }
        noticedThisMinute.put(uuid, name);
        if (!sightings.offer(new Sighting(uuid, name, day, epochMinute, session, serverId))) {
            noticedThisMinute.remove(uuid, name);
        }
    }

    /** Decides off the client thread whether a sighting can be credited from cache or needs the API. */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Sighting sighting = sightings.take();
                if (!mojang.needsFetch(sighting.uuid(), sighting.username())) {
                    credit(sighting);
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
                LOGGER.debug("Player observation failed", e);
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
                LOGGER.debug("Mojang verification failed", e);
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
            players.recordLivePlay(sighting.uuid(), sighting.username(), sighting.day(), "live:" + sighting.sessionId(), sighting.serverId()),
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

    private record Sighting(UUID uuid, String username, LocalDate day, long epochMinute, String sessionId, String serverId) {
    }
}
