package me.wolfii.haveiplayedwith.profile;

import me.wolfii.haveiplayedwith.store.PlayerStore;
import me.wolfii.haveiplayedwith.store.ProfileMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileApiTest {
    @TempDir
    Path temp;

    private PlayerStore open() {
        return new PlayerStore(temp.resolve("store.db"));
    }

    @Test
    void needsFetchOnFreshNameMismatch() {
        try (PlayerStore players = open()) {
            ProfileApi profiles = new ProfileApi(players.profiles());
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            players.profiles().put(new ProfileMapping(uuid, "Steve", Instant.now()));
            assertFalse(profiles.needsFetch(uuid, "Steve"));
            assertFalse(profiles.needsFetch(uuid, "steve"));
            assertTrue(profiles.needsFetch(uuid, "Alex"));
            assertTrue(profiles.matchesCachedName(uuid, "Steve"));
            assertFalse(profiles.matchesCachedName(uuid, "Alex"));
        }
    }

    @Test
    void cachedUuidMissSkipsFetchUntilStale() {
        try (PlayerStore players = open()) {
            ProfileApi profiles = new ProfileApi(players.profiles());
            UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
            players.profiles().put(new ProfileMapping(uuid, "", Instant.now()));
            assertFalse(profiles.needsFetch(uuid, "NPC"));
            assertFalse(profiles.matchesCachedName(uuid, "NPC"));
            assertTrue(profiles.lookupUuid(uuid).isEmpty());

            players.profiles().put(new ProfileMapping(uuid, "", Instant.now().minus(ProfileApi.STALE_AFTER).minusSeconds(1)));
            profiles = new ProfileApi(players.profiles());
            assertTrue(profiles.needsFetch(uuid, "NPC"));
            assertFalse(profiles.matchesCachedName(uuid, "NPC"));
        }
    }

    @Test
    void rememberCurrentReplacesPreviousName() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        try (PlayerStore players = open()) {
            players.profiles().put(new ProfileMapping(uuid, "Steve", Instant.parse("2026-08-01T00:00:00Z")));
            players.profiles().put(new ProfileMapping(uuid, "Alex", Instant.parse("2026-08-02T00:00:00Z")));
            assertTrue(players.profiles().byName("steve").isEmpty());
            ProfileMapping current = players.profiles().byName("alex").orElseThrow();
            assertEquals(uuid, current.uuid());
            assertEquals(Instant.parse("2026-08-02T00:00:00Z"), current.lastValid());
            assertEquals("Alex", players.profiles().byUuid(uuid).orElseThrow().username());
        }
    }

    @Test
    void rememberCurrentPersistsBothDirections() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        try (PlayerStore players = open()) {
            ProfileApi profiles = new ProfileApi(players.profiles());
            profiles.rememberCurrent(uuid, "Alex");
            assertFalse(profiles.needsFetch(uuid, "Alex"));
            profiles.rememberCurrent(uuid, "Alex");
        }
        try (PlayerStore players = open()) {
            ProfileApi profiles = new ProfileApi(players.profiles());
            ProfileMapping byUuid = profiles.cached(uuid).orElseThrow();
            assertEquals("Alex", byUuid.username());
            ProfileMapping byName = players.profiles().byName("alex").orElseThrow();
            assertEquals(uuid, byName.uuid());
            assertFalse(profiles.needsFetch(uuid, "alex"));
        }
    }
}
