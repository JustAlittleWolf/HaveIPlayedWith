package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinuteObserverTest {
    @Test
    void aRefusedWriteLeavesThePlayerUnnoted() {
        MinuteObserver observer = new MinuteObserver(null, null) {
            @Override
            void tick(Minecraft client, long epochMinute) {
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
        MinuteObserver observer = new MinuteObserver(null, null) {
            @Override
            void tick(Minecraft client, long epochMinute) {
            }
        };
        assertTrue(observer.prune(10));
        assertFalse(observer.prune(10));
        assertTrue(observer.prune(11));
    }
}
