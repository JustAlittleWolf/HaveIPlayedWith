package me.wolfii.haveiplayedwith.store;

import org.dizitart.no2.collection.Document;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.dizitart.no2.collection.Document.createDocument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StoreIdsTest {
    private static final UUID STEVE = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    @Test
    void storesTheUuidAsTwoLongsNotTheStringForm() {
        Document document = StoreIds.putUuid(createDocument(), STEVE);
        assertEquals(STEVE.getMostSignificantBits(), document.get(StoreSchema.UUID_HI));
        assertEquals(STEVE.getLeastSignificantBits(), document.get(StoreSchema.UUID_LO));
        assertInstanceOf(Long.class, document.get(StoreSchema.UUID_HI));
        assertInstanceOf(Long.class, document.get(StoreSchema.UUID_LO));
        assertEquals(STEVE, StoreIds.uuid(document));
        UUID negative = new UUID(-1L, -2L);
        Document packed = StoreIds.putUuid(createDocument(), negative);
        assertEquals(negative, StoreIds.uuid(packed));
        assertFalse(StoreIds.key(STEVE).contains("-"));
        assertEquals(32, StoreIds.key(STEVE).length());
    }
}
