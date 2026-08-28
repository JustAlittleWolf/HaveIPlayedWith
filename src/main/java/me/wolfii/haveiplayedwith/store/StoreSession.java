package me.wolfii.haveiplayedwith.store;

import com.google.gson.Gson;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Opened MVStore maps plus the single worker thread that may touch them.
 */
final class StoreSession implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final StoreWorker worker;
    final MVMap<String, String> players;
    final MVMap<String, String> history;
    final MVMap<String, String> nameIndex;
    final MVMap<String, String> playDays;
    final MVMap<String, String> playSessions;
    final MVMap<String, String> playServers;
    final MVMap<String, String> mojangUuid;
    final MVMap<String, String> mojangName;
    final MVMap<String, String> imports;

    private StoreSession(StoreWorker worker, MVStore store) {
        this.worker = worker;
        this.players = store.openMap("players");
        this.history = store.openMap("username_history");
        this.nameIndex = store.openMap("name_index");
        this.playDays = store.openMap("play_days");
        this.playSessions = store.openMap("play_sessions");
        this.playServers = store.openMap("play_servers");
        this.mojangUuid = store.openMap("mojang_uuid");
        this.mojangName = store.openMap("mojang_name");
        this.imports = store.openMap("import_progress");
    }

    static StoreSession open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            MVStore store = new MVStore.Builder()
                .fileName(file.toAbsolutePath().toString())
                .compress()
                .autoCommitDisabled()
                .open();
            return new StoreSession(new StoreWorker(store), store);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open HaveIPlayedWith database at " + file, e);
        }
    }

    Gson gson() {
        return GSON;
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
