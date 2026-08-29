package me.wolfii.haveiplayedwith.store;

import org.dizitart.no2.collection.Document;

import java.util.HexFormat;
import java.util.UUID;

/**
 * Players are identified by the UUID's two longs, not {@link UUID#toString()}.
 * Document keys pack those longs as 32 hex digits so Nitrite can unique-index
 * a single comparable field.
 */
final class StoreIds {
    private static final HexFormat HEX = HexFormat.of();

    private StoreIds() {
    }

    static long hi(UUID uuid) {
        return uuid.getMostSignificantBits();
    }

    static long lo(UUID uuid) {
        return uuid.getLeastSignificantBits();
    }

    static UUID uuid(Document document) {
        return new UUID(asLong(document.get(StoreSchema.UUID_HI)), asLong(document.get(StoreSchema.UUID_LO)));
    }

    static Document putUuid(Document document, UUID uuid) {
        document.put(StoreSchema.UUID_HI, uuid.getMostSignificantBits());
        document.put(StoreSchema.UUID_LO, uuid.getLeastSignificantBits());
        return document;
    }

    static String key(UUID uuid) {
        return HEX.toHexDigits(uuid.getMostSignificantBits()) + HEX.toHexDigits(uuid.getLeastSignificantBits());
    }

    static String key(UUID uuid, String suffix) {
        return key(uuid) + '\t' + suffix;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
