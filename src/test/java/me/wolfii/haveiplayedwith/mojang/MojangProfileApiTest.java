package me.wolfii.haveiplayedwith.mojang;

import me.wolfii.haveiplayedwith.store.PlayerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangProfileApiTest {
    @TempDir
    Path temp;

    @Test
    void needsFetchOnFreshNameMismatch() {
        try (PlayerStore players = new PlayerStore(temp.resolve("players.mv"))) {
            MojangProfileApi mojang = new MojangProfileApi(players.mojangProfiles());
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            players.mojangProfiles().putUuid(uuid, "Steve", Instant.now());
            assertFalse(mojang.needsFetch(uuid, "Steve"));
            assertFalse(mojang.needsFetch(uuid, "steve"));
            assertTrue(mojang.needsFetch(uuid, "Alex"));
        }
    }
}
