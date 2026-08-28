package me.wolfii.haveiplayedwith.crafty;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CraftyPlayerApiTest {
    @Test
    void remembersLookupsInMemoryWithoutACap() {
        CraftyPlayerApi crafty = new CraftyPlayerApi();
        CraftyPlayer player = new CraftyPlayer(
            UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"),
            "Alex",
            List.of(),
            true
        );
        for (int i = 0; i < 12_000; i++) {
            crafty.remember("Player" + i, player);
        }
        assertEquals(12_000, crafty.memorySize());
        assertSame(player, crafty.lookup("Player11999").orElseThrow());
        assertSame(player, crafty.lookup("player0").orElseThrow());
    }
}
