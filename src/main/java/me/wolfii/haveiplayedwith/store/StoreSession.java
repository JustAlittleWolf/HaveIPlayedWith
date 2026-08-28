package me.wolfii.haveiplayedwith.store;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Opened MVStore maps plus the single worker thread that may touch them.
 */
final class StoreSession implements AutoCloseable {
    private final StoreWorker worker;
    final MVMap<String, byte[]> players;
    final MVMap<String, byte[]> history;
    final MVMap<String, Long> nameIndex;
    final MVMap<String, Long> playDays;
    final MVMap<String, Long> playSessions;
    final MVMap<String, Long> playServers;
    final MVMap<String, byte[]> mojangUuid;
    final MVMap<String, byte[]> mojangName;
    final MVMap<String, byte[]> crafty;
    final MVMap<String, byte[]> imports;

    private StoreSession(StoreWorker worker, MVStore store) {
        this.worker = worker;
        this.players = StoreMaps.bytes(store, StoreMaps.PLAYERS);
        this.history = StoreMaps.bytes(store, StoreMaps.HISTORY);
        this.nameIndex = StoreMaps.longs(store, StoreMaps.NAME_INDEX);
        this.playDays = StoreMaps.longs(store, StoreMaps.PLAY_DAYS);
        this.playSessions = StoreMaps.longs(store, StoreMaps.PLAY_SESSIONS);
        this.playServers = StoreMaps.longs(store, StoreMaps.PLAY_SERVERS);
        this.mojangUuid = StoreMaps.bytes(store, StoreMaps.MOJANG_UUID);
        this.mojangName = StoreMaps.bytes(store, StoreMaps.MOJANG_NAME);
        this.crafty = StoreMaps.bytes(store, StoreMaps.CRAFTY);
        this.imports = StoreMaps.bytes(store, StoreMaps.IMPORTS);
        StoreMaps.strings(store, StoreMaps.META).putIfAbsent(StoreMaps.SCHEMA_KEY, Integer.toString(StoreMaps.SCHEMA));
    }

    static StoreSession open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            MVStore store = new MVStore.Builder()
                .fileName(file.toAbsolutePath().toString())
                .compress()
                .autoCommitDisabled()
                .open();
            StoreMigrator.migrateIfNeeded(store);
            StoreSession session = new StoreSession(new StoreWorker(store), store);
            store.commit();
            return session;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    <T> T call(Callable<T> task) {
        return worker.call(task);
    }

    void run(StoreWork task) {
        worker.run(task);
    }

    @Override
    public void close() {
        worker.close();
    }
}
