package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportStatusTest {
    @Test
    void storesTheNamesEarlierVersionsWrote() {
        assertEquals("running", ImportStatus.RUNNING.storageName());
        assertEquals("stopped", ImportStatus.STOPPED.storageName());
        assertEquals("done", ImportStatus.DONE.storageName());
    }

    @Test
    void readsBackEveryStoredName() {
        for (ImportStatus status : ImportStatus.values()) {
            assertEquals(status, ImportStatus.fromStorage(status.storageName()));
        }
    }

    @Test
    void unreadableValuesDoNotResumeAnImport() {
        assertEquals(ImportStatus.STOPPED, ImportStatus.fromStorage(null));
        assertEquals(ImportStatus.STOPPED, ImportStatus.fromStorage(""));
        assertEquals(ImportStatus.STOPPED, ImportStatus.fromStorage("paused"));
    }
}
