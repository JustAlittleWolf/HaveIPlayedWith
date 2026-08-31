package me.wolfii.haveiplayedwith.store;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Binary layout for player and profile rows. Values are length-prefixed bytes
 * with no per-row field names. UUIDs are two big-endian longs (16 bytes).
 * Minecraft usernames use a 6-bit {@code [A-Za-z0-9_]} pack; other strings are UTF-8.
 * Older rows are rewritten to {@link #VERSION} by chained version updater functions
 * before they are decoded.
 */
final class StoreCodec {
    static final int VERSION = 2;
    private static final String NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";
    private static final int UTF_FLAG = 0x80;
    /** Each updater reads version {@code n} and must write version {@code n + 1}. */
    private static final Map<Integer, UnaryOperator<byte[]>> PLAYER_UPDATES = Map.of(
        1, StoreCodec::updatePlayer1
    );
    private static final Map<Integer, UnaryOperator<byte[]>> PROFILE_UPDATES = Map.of(
        1, StoreCodec::updateProfile1
    );

    private StoreCodec() {
    }

    static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static byte[] encodePlayer(PlayerRecord player) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VERSION);
        int flags = player.note.isBlank() ? 0 : 1;
        out.write(flags);
        writeName(out, player.username);
        if (flags != 0) {
            writeUtf(out, player.note);
            writeLong(out, player.noteTakenAt);
        }
        writeUnsignedInt(out, player.totalMinutes);
        writeUnsignedInt(out, player.sessionCount);
        writeUnsignedInt(out, player.daysPlayed);
        writeUnsignedInt(out, player.recentDays.size());
        for (int day : player.recentDays) {
            writeUnsignedInt(out, day);
        }
        out.write(player.sessions.size());
        for (PlayerRecord.Session session : player.sessions) {
            writeUtf(out, session.id);
            writeUnsignedInt(out, session.minutes);
        }
        out.write(player.servers.size());
        for (ServerPlay server : player.servers) {
            writeUtf(out, server.serverId());
            writeUnsignedInt(out, server.minutes());
        }
        out.write(player.names.size());
        for (SeenName name : player.names) {
            writeName(out, name.username());
            writeLong(out, name.lastSeen().toEpochMilli());
        }
        return out.toByteArray();
    }

    static PlayerRecord decodePlayer(UUID uuid, byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(toCurrent(bytes, PLAYER_UPDATES, "player"));
        int version = in.get() & 0xff;
        if (version != VERSION) {
            throw new IllegalStateException("Unsupported HaveIPlayedWith player row version " + version);
        }
        PlayerRecord player = new PlayerRecord(uuid);
        int flags = in.get() & 0xff;
        player.username = readName(in);
        if ((flags & 1) != 0) {
            player.note = readUtf(in);
            player.noteTakenAt = in.getLong();
        }
        player.totalMinutes = readUnsignedInt(in);
        player.sessionCount = (int) readUnsignedInt(in);
        player.daysPlayed = (int) readUnsignedInt(in);
        int dayCount = (int) readUnsignedInt(in);
        for (int i = 0; i < dayCount; i++) {
            player.recentDays.add((int) readUnsignedInt(in));
        }
        int sessionCount = in.get() & 0xff;
        for (int i = 0; i < sessionCount; i++) {
            player.sessions.add(new PlayerRecord.Session(readUtf(in), readUnsignedInt(in)));
        }
        int serverCount = in.get() & 0xff;
        for (int i = 0; i < serverCount; i++) {
            player.servers.add(new ServerPlay(readUtf(in), readUnsignedInt(in)));
        }
        int nameCount = in.get() & 0xff;
        for (int i = 0; i < nameCount; i++) {
            player.names.add(new SeenName(readName(in), Instant.ofEpochMilli(in.getLong())));
        }
        return player;
    }

    static byte[] encodeProfile(ProfileMapping mapping) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VERSION);
        writeLong(out, mapping.lastValid().toEpochMilli());
        writeName(out, mapping.username() == null ? "" : mapping.username());
        return out.toByteArray();
    }

    static ProfileMapping decodeProfile(UUID uuid, byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(toCurrent(bytes, PROFILE_UPDATES, "profile"));
        int version = in.get() & 0xff;
        if (version != VERSION) {
            throw new IllegalStateException("Unsupported HaveIPlayedWith profile row version " + version);
        }
        Instant lastValid = Instant.ofEpochMilli(in.getLong());
        String username = readName(in);
        return new ProfileMapping(uuid, username.isEmpty() ? null : username, lastValid);
    }

    static byte[] encodeMillis(long millis) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(millis);
        return buffer.array();
    }

    static long decodeMillis(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    static byte[] nameKey(String usernameLower) {
        return usernameLower.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] toCurrent(byte[] bytes, Map<Integer, UnaryOperator<byte[]>> updates, String kind) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Empty HaveIPlayedWith " + kind + " row");
        }
        byte[] current = bytes;
        int version = current[0] & 0xff;
        if (version > VERSION) {
            throw new IllegalStateException("Unsupported HaveIPlayedWith " + kind + " row version " + version);
        }
        while (version < VERSION) {
            UnaryOperator<byte[]> update = updates.get(version);
            if (update == null) {
                throw new IllegalStateException("No HaveIPlayedWith " + kind + " migration from version " + version);
            }
            current = update.apply(current);
            if (current == null || current.length == 0) {
                throw new IllegalStateException("HaveIPlayedWith " + kind + " migration from version " + version + " returned an empty row");
            }
            int next = current[0] & 0xff;
            if (next <= version) {
                throw new IllegalStateException("HaveIPlayedWith " + kind + " migration from version " + version + " did not advance");
            }
            version = next;
        }
        return current;
    }

    /** v1 stored the play-day list length in one byte. */
    private static byte[] updatePlayer1(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if ((in.get() & 0xff) != 1) {
            throw new IllegalStateException("Expected HaveIPlayedWith player row version 1");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        int flags = in.get() & 0xff;
        out.write(flags);
        writeName(out, readName(in));
        if ((flags & 1) != 0) {
            writeUtf(out, readUtf(in));
            writeLong(out, in.getLong());
        }
        writeUnsignedInt(out, readUnsignedInt(in));
        writeUnsignedInt(out, readUnsignedInt(in));
        writeUnsignedInt(out, readUnsignedInt(in));
        int dayCount = in.get() & 0xff;
        writeUnsignedInt(out, dayCount);
        for (int i = 0; i < dayCount; i++) {
            writeUnsignedInt(out, readUnsignedInt(in));
        }
        byte[] rest = new byte[in.remaining()];
        in.get(rest);
        out.writeBytes(rest);
        return out.toByteArray();
    }

    /** v1 profile payload matches v2; only the version byte changes. */
    private static byte[] updateProfile1(byte[] bytes) {
        if ((bytes[0] & 0xff) != 1) {
            throw new IllegalStateException("Expected HaveIPlayedWith profile row version 1");
        }
        byte[] next = bytes.clone();
        next[0] = 2;
        return next;
    }

    private static void writeName(ByteArrayOutputStream out, String name) {
        if (name == null || name.isEmpty()) {
            out.write(0);
            return;
        }
        byte[] packed = packName(name);
        if (packed != null) {
            out.write(name.length());
            out.writeBytes(packed);
            return;
        }
        byte[] utf = name.getBytes(StandardCharsets.UTF_8);
        if (utf.length > 127) {
            throw new IllegalArgumentException("name too long");
        }
        out.write(UTF_FLAG | utf.length);
        out.writeBytes(utf);
    }

    private static String readName(ByteBuffer in) {
        int header = in.get() & 0xff;
        if (header == 0) {
            return "";
        }
        if ((header & UTF_FLAG) != 0) {
            return readUtfBytes(in, header & ~UTF_FLAG);
        }
        int chars = header;
        int packedLen = (chars * 6 + 7) / 8;
        byte[] packed = new byte[packedLen];
        in.get(packed);
        return unpackName(packed, chars);
    }

    private static byte[] packName(String name) {
        int bitBuf = 0;
        int bitCount = 0;
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (int i = 0; i < name.length(); i++) {
            int code = NAME_CHARS.indexOf(name.charAt(i));
            if (code < 0) {
                return null;
            }
            bitBuf = (bitBuf << 6) | code;
            bitCount += 6;
            if (bitCount >= 8) {
                bitCount -= 8;
                packed.write((bitBuf >>> bitCount) & 0xff);
            }
        }
        if (bitCount > 0) {
            packed.write((bitBuf << (8 - bitCount)) & 0xff);
        }
        return packed.toByteArray();
    }

    private static String unpackName(byte[] packed, int chars) {
        char[] out = new char[chars];
        int bitBuf = 0;
        int bitCount = 0;
        int index = 0;
        for (byte value : packed) {
            bitBuf = (bitBuf << 8) | (value & 0xff);
            bitCount += 8;
            while (bitCount >= 6 && index < chars) {
                bitCount -= 6;
                out[index++] = NAME_CHARS.charAt((bitBuf >>> bitCount) & 0x3f);
            }
        }
        return new String(out);
    }

    private static void writeUtf(ByteArrayOutputStream out, String value) {
        byte[] utf = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (utf.length > 0xffff) {
            throw new IllegalArgumentException("string too long");
        }
        out.write((utf.length >>> 8) & 0xff);
        out.write(utf.length & 0xff);
        out.writeBytes(utf);
    }

    private static String readUtf(ByteBuffer in) {
        int length = ((in.get() & 0xff) << 8) | (in.get() & 0xff);
        return readUtfBytes(in, length);
    }

    private static String readUtfBytes(ByteBuffer in, int length) {
        byte[] utf = new byte[length];
        in.get(utf);
        return new String(utf, StandardCharsets.UTF_8);
    }

    private static void writeUnsignedInt(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24) & 0xff);
        out.write((int) (value >>> 16) & 0xff);
        out.write((int) (value >>> 8) & 0xff);
        out.write((int) value & 0xff);
    }

    private static long readUnsignedInt(ByteBuffer in) {
        return in.getInt() & 0xffffffffL;
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        writeUnsignedInt(out, value >>> 32);
        writeUnsignedInt(out, value);
    }
}