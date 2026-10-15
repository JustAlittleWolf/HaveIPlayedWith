package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import me.wolfii.haveiplayedwith.store.ProfileMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ObservationManagerTest {
    @TempDir
    Path temp;

    @Test
    void processesSightingsAndActivityWritesWithOneSharedSession() throws Exception {
        UUID player = UUID.randomUUID();
        UUID sentinel = UUID.randomUUID();
        LocalDate day = LocalDate.now();
        try (PlayerStore players = new PlayerStore(temp.resolve("store.db"))) {
            players.profiles().put(new ProfileMapping(player, "Steve", Instant.now()));
            players.profiles().put(new ProfileMapping(sentinel, "Alex", Instant.now()));
            String sessionId;
            try (ObservationManager manager = new ObservationManager(players, new ProfileApi(players.profiles()))) {
                sessionId = manager.liveSessionId();
                assertTrue(manager.offerActivityWrite(() -> players.recordAfkMinute(player)));
                assertTrue(manager.offerActivityWrite(() -> players.recordNearbyMinute(player)));
                assertTrue(manager.offerSightings(List.of(
                    new ObservationManager.Sighting(player, "Steve", day, 10, "world/Survival"),
                    new ObservationManager.Sighting(player, "Steve", day, 10, "world/Survival"),
                    new ObservationManager.Sighting(player, "Steve", day, 11, "world/Survival"),
                    new ObservationManager.Sighting(sentinel, "Alex", day, 11, "world/Survival")
                )));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (players.get(sentinel).isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(players.get(sentinel).isPresent(), "The manager must dispatch the complete sighting batch");
            }
            var snapshot = players.get(player).orElseThrow();
            assertEquals(2, snapshot.totalMinutes());
            assertEquals(1, snapshot.sessionCount());
            assertEquals(2, players.sessionMinutes(player, sessionId));
            assertEquals(1, snapshot.afkMinutes());
            assertEquals(1, snapshot.nearbyMinutes());
        }
    }

    @Test
    void closeDrainsAcceptedWritesAndRejectsNewOnes() throws Exception {
        ObservationManager queue = new ObservationManager(null, null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        try {
            assertTrue(queue.offerActivityWrite(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(queue.offerActivityWrite(completed::incrementAndGet));
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(release::countDown);
            queue.close();
            assertEquals(1, completed.get());
            assertFalse(queue.offerActivityWrite(completed::incrementAndGet));
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test
    void aFailedWriteDoesNotStopLaterWrites() {
        ObservationManager queue = new ObservationManager(null, null);
        AtomicInteger completed = new AtomicInteger();
        assertTrue(queue.offerActivityWrite(() -> { throw new IllegalStateException("test failure"); }));
        assertTrue(queue.offerActivityWrite(completed::incrementAndGet));
        queue.close();
        assertEquals(1, completed.get());
    }
}
