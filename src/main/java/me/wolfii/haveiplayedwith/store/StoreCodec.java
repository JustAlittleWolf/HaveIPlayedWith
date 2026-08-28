package me.wolfii.haveiplayedwith.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Compact on-disk encoding for {@link StoreRows}. Length-prefixed UTF-8, fixed-width numbers.
 */
final class StoreCodec {
    private StoreCodec() {
    }

    static byte[] player(StoreRows.PlayerRow row) {
        return write(out -> {
            putString(out, row.currentUsername());
            putString(out, row.note());
            out.writeLong(row.noteTakenAt());
            out.writeLong(row.totalMinutes());
            out.writeInt(row.sessionCount());
        });
    }

    static StoreRows.PlayerRow player(byte[] data) {
        return read(data, in -> new StoreRows.PlayerRow(
            getString(in),
            getString(in),
            in.readLong(),
            in.readLong(),
            in.readInt()
        ));
    }

    static byte[] history(StoreRows.HistoryRow row) {
        return write(out -> {
            putString(out, row.username());
            out.writeLong(row.lastSeen());
        });
    }

    static StoreRows.HistoryRow history(byte[] data) {
        return read(data, in -> new StoreRows.HistoryRow(getString(in), in.readLong()));
    }

    static byte[] mojangUuid(StoreRows.MojangUuidRow row) {
        return write(out -> {
            putString(out, row.username());
            out.writeLong(row.fetchedAt());
        });
    }

    static StoreRows.MojangUuidRow mojangUuid(byte[] data) {
        return read(data, in -> new StoreRows.MojangUuidRow(getString(in), in.readLong()));
    }

    static byte[] mojangName(StoreRows.MojangNameRow row) {
        return write(out -> {
            putString(out, row.uuid());
            putString(out, row.username());
            out.writeLong(row.fetchedAt());
        });
    }

    static StoreRows.MojangNameRow mojangName(byte[] data) {
        return read(data, in -> new StoreRows.MojangNameRow(getString(in), getString(in), in.readLong()));
    }

    static byte[] crafty(StoreRows.CraftyRow row) {
        return write(out -> {
            putString(out, row.uuid());
            putString(out, row.currentUsername());
            putString(out, row.usernamesJson());
            out.writeBoolean(row.valid());
            out.writeLong(row.fetchedAt());
        });
    }

    static StoreRows.CraftyRow crafty(byte[] data) {
        return read(data, in -> new StoreRows.CraftyRow(
            getString(in),
            getString(in),
            getString(in),
            in.readBoolean(),
            in.readLong()
        ));
    }

    static byte[] imports(StoreRows.ImportRow row) {
        return write(out -> {
            out.writeLong(row.processed());
            out.writeLong(row.total());
            putString(out, row.lastTimestamp());
            out.writeLong(row.skip());
            putString(out, row.status());
            out.writeBoolean(row.silenced());
        });
    }

    static StoreRows.ImportRow imports(byte[] data) {
        return read(data, in -> new StoreRows.ImportRow(
            in.readLong(),
            in.readLong(),
            getString(in),
            in.readLong(),
            getString(in),
            in.readBoolean()
        ));
    }

    private static byte[] write(IOConsumer<DataOutput> writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try {
            DataOutputStream out = new DataOutputStream(bytes);
            writer.accept(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static <T> T read(byte[] data, IOFunction<DataInput, T> reader) {
        try {
            return reader.apply(new DataInputStream(new ByteArrayInputStream(data)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void putString(DataOutput out, String value) throws IOException {
        byte[] utf = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf.length);
        out.write(utf);
    }

    private static String getString(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 8 * 1024 * 1024) {
            throw new IOException("invalid stored string length: " + length);
        }
        byte[] utf = new byte[length];
        in.readFully(utf);
        return new String(utf, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    @FunctionalInterface
    private interface IOFunction<T, R> {
        R apply(T value) throws IOException;
    }
}
