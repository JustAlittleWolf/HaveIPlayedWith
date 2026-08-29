package me.wolfii.haveiplayedwith.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreMvTest {
    @Test
    void skipsFilesUnderTheSizeFloor() {
        assertFalse(StoreMv.worthRewriting(512 * 1024, 5));
        assertFalse(StoreMv.worthRewriting(StoreMv.MIN_FILE_BYTES - 1, 0));
    }

    @Test
    void skipsWhenChunksAreAlreadyMostlyLive() {
        assertFalse(StoreMv.worthRewriting(10L * 1024 * 1024, 80));
        assertFalse(StoreMv.worthRewriting(10L * 1024 * 1024, 51));
    }

    @Test
    void rewritesWhenAMegabyteOfGarbageIsWaiting() {
        assertTrue(StoreMv.worthRewriting(4L * 1024 * 1024, 20));
        assertTrue(StoreMv.worthRewriting(2L * 1024 * 1024, 0));
    }

    @Test
    void skipsWhenTheSavingsWouldBeTiny() {
        assertFalse(StoreMv.worthRewriting(StoreMv.MIN_FILE_BYTES, 50));
    }
}
