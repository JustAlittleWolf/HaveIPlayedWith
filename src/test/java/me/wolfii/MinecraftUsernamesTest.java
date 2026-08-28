package me.wolfii;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftUsernamesTest {
	@Test
	void acceptsJavaEditionNames() {
		assertTrue(MinecraftUsernames.isValid("Steve"));
		assertTrue(MinecraftUsernames.isValid("a_b"));
		assertTrue(MinecraftUsernames.isValid("JustAlittleWolf"));
	}

	@Test
	void rejectsInvalidNames() {
		assertFalse(MinecraftUsernames.isValid(null));
		assertFalse(MinecraftUsernames.isValid("ab"));
		assertFalse(MinecraftUsernames.isValid("thisnameiswaytoolong"));
		assertFalse(MinecraftUsernames.isValid("NPC-Guard"));
		assertFalse(MinecraftUsernames.isValid("Player 1"));
		assertFalse(MinecraftUsernames.isValid("§cSteve"));
	}
}
