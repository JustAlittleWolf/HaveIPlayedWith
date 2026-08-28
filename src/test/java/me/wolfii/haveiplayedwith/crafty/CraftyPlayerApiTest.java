package me.wolfii.haveiplayedwith.crafty;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftyPlayerApiTest {
    private static final UUID DINNERBONE = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
    private static final UUID CURRENT_GORMAN = UUID.fromString("15cf0fd1-f5c4-46b3-bf00-75ab04d66386");

    @Test
    void remembersLookupsInMemoryWithoutACap() {
        CraftyPlayerApi crafty = new CraftyPlayerApi();
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        CraftyPlayer last = null;
        CraftyPlayer first = null;
        for (int i = 0; i < 12_000; i++) {
            String name = "Player" + i;
            CraftyPlayer player = new CraftyPlayer(
                UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"),
                name,
                List.of(new CraftyNameHistory.Entry(name, null)),
                true
            );
            if (i == 0) {
                first = player;
            }
            last = player;
            crafty.remember(name, player);
        }
        assertEquals(12_000, crafty.memorySize());
        assertSame(last, crafty.lookupHeld("Player11999", now).orElseThrow());
        assertSame(first, crafty.lookupHeld("player0", now).orElseThrow());
    }

    @Test
    void lookupHeldIgnoresACurrentHolderWhoDidNotHaveTheNameYet() {
        CraftyPlayerApi crafty = new CraftyPlayerApi();
        CraftyPlayer current = new CraftyPlayer(
            CURRENT_GORMAN,
            "GormanRoddy",
            List.of(
                new CraftyNameHistory.Entry("GormanRoddy", Instant.parse("2026-02-16T22:45:47Z")),
                new CraftyNameHistory.Entry("narcosul", Instant.parse("2025-08-06T08:52:48Z")),
                new CraftyNameHistory.Entry("DetectiveHolmes", null)
            ),
            true
        );
        CraftyPlayer dinnerbone = new CraftyPlayer(
            DINNERBONE,
            "Dinnerbone",
            List.of(
                new CraftyNameHistory.Entry("Dinnerbone", Instant.parse("2026-01-07T18:54:24Z")),
                new CraftyNameHistory.Entry("GormanRoddy", Instant.parse("2026-01-07T07:47:48Z")),
                new CraftyNameHistory.Entry("Dinnerbone", null)
            ),
            true
        );
        crafty.remember("GormanRoddy", current, dinnerbone);
        assertEquals(DINNERBONE, crafty.lookupHeld("GormanRoddy", Instant.parse("2026-01-07T12:00:00Z")).orElseThrow().uuid());
        assertEquals(CURRENT_GORMAN, crafty.lookupHeld("GormanRoddy", Instant.parse("2026-03-01T00:00:00Z")).orElseThrow().uuid());
        assertTrue(crafty.lookupHeld("GormanRoddy", Instant.parse("2025-01-01T00:00:00Z")).isEmpty());
    }

    @Test
    void ownerUuidsIncludeCurrentAndHistoricalHolders() {
        String body = """
            {"success":true,"data":{
              "username":"GormanRoddy",
              "current_player":{"uuid":"15cf0fd1-f5c4-46b3-bf00-75ab04d66386","username":"GormanRoddy"},
              "historical_players":[{"uuid":"61699b2e-d327-4a01-9f1e-0ea8c3f06bc6","username":"Dinnerbone"}]
            }}
            """;
        assertEquals(List.of(CURRENT_GORMAN, DINNERBONE), CraftyPlayerApi.ownerUuids(body));
    }

    @Test
    void parsePlayerReadsNameHistoryIntervals() {
        String body = """
            {"success":true,"data":{
              "uuid":"61699b2e-d327-4a01-9f1e-0ea8c3f06bc6",
              "username":"Dinnerbone",
              "usernames":[
                {"username":"Dinnerbone","changed_at":"2026-01-07T19:54:24.560+01:00"},
                {"username":"GormanRoddy","changed_at":"2026-01-07T08:47:48.573+01:00"},
                {"username":"Dinnerbone","changed_at":null}
              ]
            }}
            """;
        CraftyPlayer player = CraftyPlayerApi.parsePlayer(body).orElseThrow();
        assertEquals(DINNERBONE, player.uuid());
        assertEquals("Dinnerbone", player.currentUsername());
        assertEquals(3, player.history().size());
        assertTrue(CraftyNameHistory.heldNameAt(player.history(), "GormanRoddy", Instant.parse("2026-01-07T12:00:00Z")));
        assertFalse(CraftyNameHistory.heldNameAt(player.history(), "GormanRoddy", Instant.parse("2026-03-01T00:00:00Z")));
    }
}
