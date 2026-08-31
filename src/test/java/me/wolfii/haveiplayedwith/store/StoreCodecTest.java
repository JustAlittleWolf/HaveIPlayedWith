package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
        assertEquals(List.of(
            (int) LocalDate.of(2026, 8, 1).toEpochDay(),
            (int) LocalDate.of(2026, 8, 2).toEpochDay()
        ), copy.recentDays);
        assertTrue(StoreCodec.encodePlayer(player).length < 200);
    }

    @Test
    void playerRoundTripKeepsEveryPlayDayAndCapsSessions() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        PlayerRecord player = new PlayerRecord(uuid);
        player.setCurrentUsername("Steve");
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int day = 0; day < 300; day++) {
            player.credit(start.plusDays(day), "live:" + day, "hypixel.net");
        }
        assertEquals(300, player.daysPlayed);
        assertEquals(300, player.recentDays.size());
        assertEquals(PlayerRecord.KEEP_RECENT, player.sessions.size());

        PlayerRecord copy = StoreCodec.decodePlayer(uuid, StoreCodec.encodePlayer(player));
        assertEquals(300, copy.daysPlayed);
        assertEquals(300, copy.recentDays.size());
        assertEquals((int) start.toEpochDay(), copy.recentDays.getFirst());
        assertEquals((int) start.plusDays(299).toEpochDay(), copy.recentDays.getLast());
        assertEquals(PlayerRecord.KEEP_RECENT, copy.sessions.size());
        assertEquals("live:299", copy.sessions.getLast().id);
    }

    @Test
    void readsVersionOnePlayerRowsWithASingleByteDayCount() {
        UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        byte[] versionOne = {
            1, 0, 0,
            0, 0, 0, 1,
            0, 0, 0, 1,
            0, 0, 0, 2,
            2,
            0, 0, 0x4e, 0x46,
            0, 0, 0x4e, 0x47,
            0, 0, 0
        };
        PlayerRecord player = StoreCodec.decodePlayer(uuid, versionOne);
        assertEquals(1, player.totalMinutes);
        assertEquals(1, player.sessionCount);
        assertEquals(2, player.daysPlayed);
        assertEquals(List.of(0x4e46, 0x4e47), player.recentDays);
        assertTrue(player.sessions.isEmpty());
        assertTrue(player.servers.isEmpty());
        assertTrue(player.names.isEmpty());
    }
}