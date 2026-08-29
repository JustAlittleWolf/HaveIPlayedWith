package me.wolfii.haveiplayedwith.importing;

import me.wolfii.allthelogs.api.AllTheLogs;
import me.wolfii.allthelogs.api.ChatEntry;
import me.wolfii.allthelogs.api.ChatQuery;
import me.wolfii.allthelogs.api.LogDatabase;
import me.wolfii.haveiplayedwith.ModLog;
import me.wolfii.haveiplayedwith.ModThreads;
import me.wolfii.haveiplayedwith.chat.ImportMessages;
import me.wolfii.haveiplayedwith.crafty.CraftyPlayer;
import me.wolfii.haveiplayedwith.crafty.CraftyPlayerApi;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.ImportProgress;
import me.wolfii.haveiplayedwith.store.ImportStatus;
import me.wolfii.haveiplayedwith.store.PlayerStore;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

public final class AllTheLogsImporter {
    private static final int PAGE_SIZE = 400;
    private static final long SAVE_EVERY = 250;
    /** Most log lines hold no username, so throttle by time rather than by lines walked. */
    private static final long REPORT_INTERVAL_NANOS = 15_000_000_000L;
    private final PlayerStore players;
    private final CraftyPlayerApi crafty;
    private final MojangProfileApi mojang;
    private final ImportControls controls;
    private final ExecutorService worker = ModThreads.singleWorker("import");

    public AllTheLogsImporter(PlayerStore players, CraftyPlayerApi crafty, MojangProfileApi mojang, ImportControls controls) {
        this.players = players;
        this.crafty = crafty;
        this.mojang = mojang;
        this.controls = controls;
    }

    public void resumeIfNeeded() {
        if (!controls.trySchedule()) {
            return;
        }
        worker.execute(() -> {
            try {
                Optional<ImportProgress> progress = players.importProgress().get(ImportProgress.SOURCE_ALLTHELOGS);
                progress.ifPresent(controls::save);
                if (progress.isPresent() && progress.get().status() == ImportStatus.RUNNING) {
                    controls.progress(ImportMessages.resuming());
                    runImport(progress.get());
                }
            } finally {
                controls.unschedule();
            }
        });
    }

    public void startFromCommand() {
        if (!controls.trySchedule()) {
            controls.chat(ImportMessages.alreadyRunning());
            return;
        }
        controls.clearStop();
        worker.execute(() -> {
            try {
                Optional<ImportProgress> existing = players.importProgress().get(ImportProgress.SOURCE_ALLTHELOGS);
                ImportProgress start = existing
                    .filter(progress -> progress.status() != ImportStatus.DONE)
                    .orElseGet(() -> new ImportProgress(
                        ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportStatus.RUNNING,
                        existing.map(ImportProgress::silenced).orElse(controls.silenced())
                    ));
                start = start.withStatus(ImportStatus.RUNNING);
                controls.chat(ImportMessages.starting());
                runImport(start);
            } finally {
                controls.unschedule();
            }
        });
    }

    private void runImport(ImportProgress start) {
        try {
            waitUntilOpen();
            if (controls.stopRequested()) {
                controls.saveStopped(start);
                return;
            }
            LogDatabase logs = AllTheLogs.database();
            if (!logs.isOpen()) {
                controls.progress(ImportMessages.notReady());
                controls.save(start.withStatus(ImportStatus.RUNNING));
                return;
            }
            long total = start.total() >= 0 ? start.total() : logs.countMatches(ChatQuery.all()).join();
            Cursor cursor = Cursor.resuming(start);
            save(cursor, total, ImportStatus.RUNNING);
            if (readEntries(logs, cursor, total)) {
                save(cursor, total, ImportStatus.DONE);
                controls.chat(ImportMessages.finished(cursor.processed()));
            }
        } catch (Exception e) {
            ModLog.LOGGER.warn("AllTheLogs import failed", e);
            controls.chat(ImportMessages.failed());
        }
    }

    /**
     * Reads the log a page at a time, from wherever {@code cursor} left off.
     *
     * @return true once the log runs out, false if a stop was requested, in which case the
     *     cursor has already been saved so the next run picks up here
     */
    private boolean readEntries(LogDatabase logs, Cursor cursor, long total) {
        long lastSave = cursor.processed();
        long lastReportAt = System.nanoTime();
        while (true) {
            if (stopHere(cursor)) {
                return false;
            }
            List<ChatEntry> page = logs.findEntries(pageAt(cursor)).join();
            if (page.isEmpty()) {
                return true;
            }
            for (ChatEntry entry : page) {
                if (stopHere(cursor)) {
                    return false;
                }
                process(entry);
                cursor.advanceTo(entry.timestamp());
                if (cursor.processed() - lastSave < SAVE_EVERY) {
                    continue;
                }
                lastSave = cursor.processed();
                save(cursor, total, ImportStatus.RUNNING);
                long now = System.nanoTime();
                if (now - lastReportAt >= REPORT_INTERVAL_NANOS) {
                    lastReportAt = now;
                    report(cursor.processed(), total);
                }
            }
        }
    }

    private ChatQuery pageAt(Cursor cursor) {
        ChatQuery query = ChatQuery.all()
            .withSort(ChatQuery.Sort.ASCENDING)
            .withLimit(PAGE_SIZE);
        if (cursor.lastTimestamp() == null) {
            return query;
        }
        return query.startingAt(cursor.lastTimestamp()).withSkip(cursor.skip());
    }

    /** Records where to resume from when a stop has been requested. */
    private boolean stopHere(Cursor cursor) {
        if (!controls.stopRequested()) {
            return false;
        }
        controls.saveStopped(controls.latest().withCursor(cursor.processed(), cursor.lastTimestamp(), cursor.skip()));
        return true;
    }

    private void save(Cursor cursor, long total, ImportStatus status) {
        controls.save(new ImportProgress(
            ImportProgress.SOURCE_ALLTHELOGS,
            cursor.processed(),
            total,
            cursor.lastTimestamp(),
            cursor.skip(),
            status,
            controls.silenced()
        ));
    }

    private void process(ChatEntry entry) {
        Optional<String> username = UsernameExtractor.extract(entry.message());
        if (username.isEmpty()) {
            return;
        }
        Instant at = entry.timestamp().atZone(ZoneId.systemDefault()).toInstant();
        Optional<CraftyPlayer> player = crafty.lookupHeld(username.get(), at);
        if (player.isEmpty() || !player.get().valid() || player.get().uuid() == null) {
            return;
        }
        CraftyPlayer resolved = player.get();
        mojang.rememberCurrent(resolved.uuid(), resolved.currentUsername());
        players.recordImportedSighting(
            resolved.uuid(),
            username.get(),
            resolved.currentUsername(),
            entry.timestamp().toLocalDate(),
            ChatLogIds.sessionId(entry.chatLog()),
            at
        );
    }

    private void waitUntilOpen() {
        for (int i = 0; i < 90; i++) {
            if (controls.stopRequested() || AllTheLogs.database().isOpen()) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void report(long processed, long total) {
        if (total > 0) {
            controls.progress(ImportMessages.progress(processed, total));
        } else {
            controls.progress(ImportMessages.progressMessages(processed));
        }
    }

    /**
     * How far through the log the walk has got. Timestamps are not unique, so resuming needs
     * both the last timestamp seen and how many entries at that timestamp were already read.
     */
    private static final class Cursor {
        private long processed;
        private LocalDateTime lastTimestamp;
        private long skip;

        private Cursor(long processed, LocalDateTime lastTimestamp, long skip) {
            this.processed = processed;
            this.lastTimestamp = lastTimestamp;
            this.skip = skip;
        }

        static Cursor resuming(ImportProgress progress) {
            return new Cursor(progress.processed(), progress.lastTimestamp(), progress.skip());
        }

        void advanceTo(LocalDateTime timestamp) {
            processed++;
            if (timestamp.equals(lastTimestamp)) {
                skip++;
            } else {
                lastTimestamp = timestamp;
                skip = 1;
            }
        }

        long processed() {
            return processed;
        }

        LocalDateTime lastTimestamp() {
            return lastTimestamp;
        }

        long skip() {
            return skip;
        }
    }
}
