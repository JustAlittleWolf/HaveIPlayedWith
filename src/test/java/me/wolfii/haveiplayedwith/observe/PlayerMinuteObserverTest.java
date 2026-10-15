package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMinuteObserverTest {
    @Test
    void aRefusedWriteLeavesThePlayerUnnoted() {
        PlayerMinuteObserver observer = new PlayerMinuteObserver() {
            @Override
            void tick(Minecraft client, long epochMinute, String serverId) {
            }
        };
        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, "Steve");
        assertFalse(observer.offer(profile, 10, id -> false));
        assertTrue(observer.offer(profile, 10, id -> true));
        assertTrue(observer.offer(profile, 10, id -> false));
    }

    @Test
    void pruneRunsOncePerMinute() {
        PlayerMinuteObserver observer = new PlayerMinuteObserver() {
            @Override
            void tick(Minecraft client, long epochMinute, String serverId) {
            }
        };
        assertTrue(observer.prune(10));
        assertFalse(observer.prune(10));
        assertTrue(observer.prune(11));
    }
    @Test
    void observersQueueIndependentlyAndAcceptLaterConditionsWithinTheSameMinute() {
        PlayerMinuteObserver play = observer();
        PlayerMinuteObserver afk = observer();
        PlayerMinuteObserver nearby = observer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Steve");
        java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Predicate<GameProfile> enqueue = ignored -> {
            queued.incrementAndGet();
            return true;
        };
        assertTrue(play.offer(profile, 10, enqueue));
        assertTrue(afk.offer(profile, 10, enqueue));
        assertFalse(nearby.offer(profile, 10, ignored -> false));
        assertTrue(nearby.offer(profile, 10, enqueue));
        assertTrue(play.offer(profile, 10, enqueue));
        assertEquals(3, queued.get());
        assertTrue(play.offer(profile, 11, enqueue));
        assertEquals(4, queued.get());
    }

    @Test
    void aRejectedBatchCanBeCollectedAgainWithoutForgettingOtherMinutes() {
        PlayerMinuteObserver observer = observer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Steve");
        assertTrue(observer.offer(profile, 10, ignored -> true));
        observer.forgetQueuedMinute(profile.id(), 10);
        assertFalse(observer.offer(profile, 10, ignored -> false));
        assertTrue(observer.offer(profile, 11, ignored -> true));
        observer.forgetQueuedMinute(profile.id(), 10);
        assertTrue(observer.offer(profile, 11, ignored -> false));
    }

    @Test
    void pruneDropsOldPlayersAndClearAllowsTheSameMinuteAgain() {
        PlayerMinuteObserver observer = observer();
        GameProfile old = new GameProfile(UUID.randomUUID(), "Steve");
        GameProfile kept = new GameProfile(UUID.randomUUID(), "Alex");
        assertTrue(observer.offer(old, 39, ignored -> true));
        assertTrue(observer.offer(kept, 40, ignored -> true));
        observer.prune(100);
        assertFalse(observer.offer(old, 39, ignored -> false));
        assertTrue(observer.offer(kept, 40, ignored -> false));
        observer.clear();
        assertFalse(observer.offer(kept, 40, ignored -> false));
        assertTrue(observer.prune(100));
    }

    private static PlayerMinuteObserver observer() {
        return new PlayerMinuteObserver() {
            @Override
            void tick(Minecraft client, long epochMinute, String serverId) {
            }
        };
    }
}
