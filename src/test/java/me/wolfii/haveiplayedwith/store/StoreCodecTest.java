package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreCodecTest {
    @Test
    void uuidIsTwoBigEndianLongs() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        byte[] bytes = StoreCodec.uuidBytes(uuid);
        assertEquals(16, bytes.length);
        assertEquals(uuid, StoreCodec.uuid(bytes));
        assertEquals(uuid.getMostSignificantBits(), ((long) (bytes[0] & 0xff) << 56)
            | ((long) (bytes[1] & 0xff) << 48)
            | ((long) (bytes[2] & 0xff) << 40)
            | ((long) (bytes[3] & 0xff) << 32)
            | ((long) (bytes[4] & 0xff) << 24)
            | ((long) (bytes[5] & 0xff) << 16)
            | ((long) (bytes[6] & 0xff) << 8)
            | (bytes[7] & 0xff));
    }

    @Test
    void playerRoundTripKeepsPackedNamesAndServers() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        PlayerRecord player = new PlayerRecord(uuid);
        player.setCurrentUsername("Steve");
        player.setNote("builds farms", Instant.parse("2026-08-01T00:00:00Z").toEpochMilli());
        player.touchName("Steve", Instant.parse("2026-08-01T00:00:00Z"));
        player.credit(LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
        player.credit(LocalDate.of(2026, 8, 1), "live:one", "hypixel.net");
        player.touchName("Alex_99", Instant.parse("2026-08-02T00:00:00Z"));
        player.setCurrentUsername("Alex_99");
        player.credit(LocalDate.of(2026, 8, 2), "live:two", "world/Survival");

        PlayerRecord copy = StoreCodec.decodePlayer(uuid, StoreCodec.encodePlayer(player));
        assertEquals("Alex_99", copy.username);
        assertEquals("builds farms", copy.note);
        assertEquals(3, copy.totalMinutes);
        assertEquals(2, copy.sessionCount);
        assertEquals(2, copy.daysPlayed);
        assertEquals("Steve", copy.names.get(1).username());
        assertEquals("hypixel.net", copy.snapshot().mostPlayedServer().orElseThrow().serverId());
        assertTrue(StoreCodec.encodePlayer(player).length < 200);
    }
}