package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns the observation workers, tick dispatch, and connection lifecycle. */
public final class TickHandler implements AutoCloseable {
    private static final int MAX_ACTIVITY_WRITES = 2048;
    private final ExecutorService sightingWorker = ModThreads.singleWorker("sightings");
    private final ExecutorService profileWorker = ModThreads.singleWorker("profiles");
    private final ExecutorService activityWorker = ModThreads.singleWorker("activity-writes");
    private final BlockingQueue<Runnable> activityWrites = new ArrayBlockingQueue<>(MAX_ACTIVITY_WRITES);
    private final String sessionId = "live:" + UUID.randomUUID();
    private final List<PlayerMinuteObserver> observers;
    private String currentServerId;

    public TickHandler(PlayerStore players, ProfileApi profiles) {
        PlayerObserver playerObserver = new PlayerObserver(players, profiles, sessionId);
        AfkObserver afkObserver = new AfkObserver(players, this::offerActivityWrite);
        NearbyPlayerObserver proximityObserver = new NearbyPlayerObserver(players, this::offerActivityWrite);
        observers = List.of(afkObserver, proximityObserver, playerObserver);
        sightingWorker.execute(playerObserver::dispatchLoop);
        profileWorker.execute(playerObserver::lookupLoop);
        activityWorker.execute(this::drainActivityWrites);
    }

    public String liveSessionId() {
        return sessionId;
    }

    public void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> currentServerId = ServerId.current(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            observers.forEach(PlayerMinuteObserver::clear);
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
        for (PlayerMinuteObserver observer : observers) {
            observer.tick(client, epochMinute, currentServerId);
        }
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
}
