package me.wolfii.haveiplayedwith.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerIdTest {
    @Test
    void localWorldsUseWorldPrefix() {
        assertEquals("world/Survival", ServerId.localWorld("Survival"));
        assertEquals("world/My World", ServerId.localWorld("  My World  "));
        assertEquals("world/unnamed", ServerId.localWorld(""));
        assertEquals("world/foo bar", ServerId.localWorld("foo\tbar"));
    }

    @Test
    void remoteServersNormalizeHostAndDefaultPort() {
        assertEquals("hypixel.net", ServerId.remote("Hypixel.NET"));
        assertEquals("play.example.com", ServerId.remote("play.example.com:25565"));
        assertEquals("play.example.com:25566", ServerId.remote("play.example.com:25566"));
        assertEquals("[::1]", ServerId.remote("[::1]:25565"));
        assertEquals("[::1]:25566", ServerId.remote("[::1]:25566"));
        assertNull(ServerId.remote("   "));
    }
}
