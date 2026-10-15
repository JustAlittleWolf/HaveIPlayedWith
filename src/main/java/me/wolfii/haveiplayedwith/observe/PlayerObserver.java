package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.chat.RenameMessages;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/** Collects play sightings and processes cached names and profile lookups off the client thread. */
final class PlayerObserver extends PlayerMinuteObserver {
    /** Sightings waiting on a profile lookup, per the 500 entry buffer the API budget allows. */
    private static final int MAX_LOOKUP_BUFFER = 500;
    /** Ticks whose new sightings are still waiting to be classified. */
    private static final int MAX_SIGHTING_BATCHES = 8;
    private final PlayerStore playerStore;
    private final ProfileApi profiles;
    private final BlockingQueue<List<Sighting>> sightings = new ArrayBlockingQueue<>(MAX_SIGHTING_BATCHES);
    private final ConcurrentHashMap<UUID, Sighting> pendingLookups = new ConcurrentHashMap<>();
    private final BlockingQueue<UUID> lookups = new ArrayBlockingQueue<>(MAX_LOOKUP_BUFFER);
    private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();
    private final String sessionId;

    PlayerObserver(PlayerStore players, ProfileApi profiles, String sessionId) {
        this.playerStore = players;
        this.profiles = profiles;
        this.sessionId = sessionId;
    }

    @Override
    void clear() {
        super.clear();
        creditedMinute.clear();
    }

    @Override
    void tick(Minecraft client, long epochMinute, String serverId) {
        if (prune(epochMinute)) {
            long keepAfter = epochMinute - MEMORY_MINUTES;
            creditedMinute.values().removeIf(minute -> minute < keepAfter);
        }
        LocalDate day = LocalDate.now();
        List<Sighting> batch = new ArrayList<>();
        observeAllPlayers(client, epochMinute, profile -> {
            batch.add(new Sighting(profile.id(), profile.name(), day, epochMinute, serverId));
            return true;
        });
        if (!batch.isEmpty() && !sightings.offer(List.copyOf(batch))) {
            for (Sighting sighting : batch) {
                forgetQueuedMinute(sighting.uuid(), epochMinute);
            }
        }
    }

    /**
     * Decides off the client thread whether a sighting can be credited from cache or needs the API.
     * A cached UUID↔name match is credited with no request, even when that cache row is old.
     */
    void dispatchLoop() {
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

    void lookupLoop() {
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

    private record Sighting(UUID uuid, String username, LocalDate day, long epochMinute, String serverId) {
    }
}
