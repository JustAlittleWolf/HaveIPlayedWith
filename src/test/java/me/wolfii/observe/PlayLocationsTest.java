package me.wolfii.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayLocationsTest {
	@Test
	void localWorldsUseWorldPrefix() {
		assertEquals("world/Survival", PlayLocations.localWorld("Survival"));
		assertEquals("world/My World", PlayLocations.localWorld("  My World  "));
		assertEquals("world/unnamed", PlayLocations.localWorld(""));
		assertEquals("world/foo bar", PlayLocations.localWorld("foo\tbar"));
	}

	@Test
	void remoteServersNormalizeHostAndDefaultPort() {
		assertEquals("hypixel.net", PlayLocations.remoteServer("Hypixel.NET"));
		assertEquals("play.example.com", PlayLocations.remoteServer("play.example.com:25565"));
		assertEquals("play.example.com:25566", PlayLocations.remoteServer("play.example.com:25566"));
		assertEquals("[::1]", PlayLocations.remoteServer("[::1]:25565"));
		assertEquals("[::1]:25566", PlayLocations.remoteServer("[::1]:25566"));
		assertNull(PlayLocations.remoteServer("   "));
	}
}
