package me.wolfii.haveiplayedwith.net;

import me.wolfii.haveiplayedwith.store.PlayerDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangClientTest {
    @TempDir
    Path temp;

    @Test
    void needsFetchOnFreshNameMismatch() {
        try (PlayerDatabase database = new PlayerDatabase(temp.resolve("players.mv"))) {
            MojangClient mojang = new MojangClient(database);
            UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
            database.putMojangCache(uuid, "Steve", Instant.now());
            assertFalse(mojang.needsFetch(uuid, "Steve"));
            assertFalse(mojang.needsFetch(uuid, "steve"));
            assertTrue(mojang.needsFetch(uuid, "Alex"));
        }
    }
}
