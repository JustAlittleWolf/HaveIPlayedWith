package me.wolfii.haveiplayedwith.mojang;

import me.wolfii.haveiplayedwith.store.MojangNameCache;
import me.wolfii.haveiplayedwith.store.MojangUuidCache;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangProfileApiTest {
    @TempDir
    Path temp;

    private PlayerStore open() {
        return new PlayerStore(temp.resolve("database"));
    }

    @Test
    void needsFetchOnFreshNameMismatch() {
        try (PlayerStore players = open()) {
            MojangProfileApi mojang = new MojangProfileApi(players.mojangProfiles());
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            players.mojangProfiles().putUuid(uuid, "Steve", Instant.now());
            assertFalse(mojang.needsFetch(uuid, "Steve"));
            assertFalse(mojang.needsFetch(uuid, "steve"));
            assertTrue(mojang.needsFetch(uuid, "Alex"));
            assertTrue(mojang.matchesCachedName(uuid, "Steve"));
            assertFalse(mojang.matchesCachedName(uuid, "Alex"));
        }
    }

    @Test
    void cachedUuidMissSkipsFetchUntilStale() {
        try (PlayerStore players = open()) {
            MojangProfileApi mojang = new MojangProfileApi(players.mojangProfiles());
            UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
            players.mojangProfiles().putUuid(uuid, "", Instant.now());
            assertFalse(mojang.needsFetch(uuid, "NPC"));
            assertFalse(mojang.matchesCachedName(uuid, "NPC"));
            assertTrue(mojang.lookupUuid(uuid).isEmpty());

            players.mojangProfiles().putUuid(uuid, "", Instant.now().minus(MojangProfileApi.STALE_AFTER).minusSeconds(1));
            mojang = new MojangProfileApi(players.mojangProfiles());
            assertTrue(mojang.needsFetch(uuid, "NPC"));
            assertFalse(mojang.matchesCachedName(uuid, "NPC"));
        }
    }

    @Test
    void rememberCurrentPersistsBothDirections() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        try (PlayerStore players = open()) {
            MojangProfileApi mojang = new MojangProfileApi(players.mojangProfiles());
            mojang.rememberCurrent(uuid, "Alex");
            assertFalse(mojang.needsFetch(uuid, "Alex"));
            mojang.rememberCurrent(uuid, "Alex");
        }
        try (PlayerStore players = open()) {
            MojangProfileApi mojang = new MojangProfileApi(players.mojangProfiles());
            MojangUuidCache byUuid = mojang.cached(uuid).orElseThrow();
            assertEquals("Alex", byUuid.username());
            MojangNameCache byName = players.mojangProfiles().byName("alex").orElseThrow();
            assertEquals(uuid, byName.uuid());
            assertFalse(mojang.needsFetch(uuid, "alex"));
        }
    }
}
