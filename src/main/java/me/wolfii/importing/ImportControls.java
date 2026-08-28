package me.wolfii.importing;

import me.wolfii.command.QueryMessages;
import me.wolfii.db.ImportProgress;
import me.wolfii.db.PlayerDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Import start/stop/silence state that does not touch AllTheLogs classes, so the
 * silence and stop commands can be registered even when that mod is absent.
 */
public final class ImportControls {
    private final PlayerDatabase database;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean silenced = new AtomicBoolean(false);
    private volatile ImportProgress latest = new ImportProgress(
        ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportProgress.STATUS_STOPPED, false
    );
    private volatile Runnable startAllTheLogs;

    public ImportControls(PlayerDatabase database) {
        this.database = database;
        database.importProgress(ImportProgress.SOURCE_ALLTHELOGS).ifPresent(stored -> {
            latest = stored;
            silenced.set(stored.silenced());
        });
    }

    public void setStartAllTheLogs(Runnable startAllTheLogs) {
        this.startAllTheLogs = startAllTheLogs;
    }

    public boolean hasAllTheLogs() {
        return startAllTheLogs != null;
    }

    public void startAllTheLogs() {
        Runnable start = startAllTheLogs;
        if (start != null) {
            start.run();
        }
    }

    public boolean trySchedule() {
        return scheduled.compareAndSet(false, true);
    }

    public void unschedule() {
        scheduled.set(false);
    }

    public boolean scheduled() {
        return scheduled.get();
    }

    public void clearStop() {
        stopRequested.set(false);
    }

    public boolean stopRequested() {
        return stopRequested.get();
    }

    public boolean silenced() {
        return silenced.get();
    }

    public ImportProgress latest() {
        return latest;
    }

    public void stopFromCommand() {
        if (!scheduled.get()) {
            chat(QueryMessages.importStatus("No AllTheLogs import is running."));
            return;
        }
        stopRequested.set(true);
        chat(QueryMessages.importStatus("Stopping AllTheLogs import..."));
    }

    public void toggleSilenceFromCommand() {
        boolean next = !silenced.get();
        silenced.set(next);
        ImportProgress stored = scheduled.get()
            ? latest
            : database.importProgress(ImportProgress.SOURCE_ALLTHELOGS).orElse(latest);
        save(stored.withSilenced(next));
        chat(QueryMessages.importStatus(next
            ? "AllTheLogs import progress messages silenced."
            : "AllTheLogs import progress messages enabled."));
    }

    public void notifyIfRunning() {
        if (!scheduled.get()) {
            return;
        }
        ImportProgress progress = latest;
        progress(QueryMessages.importStillRunning(progress.processed(), progress.total()));
    }

    void save(ImportProgress progress) {
        ImportProgress stored = progress.withSilenced(silenced.get());
        latest = stored;
        database.saveImportProgress(stored);
    }

    void saveStopped(ImportProgress progress) {
        save(progress.withStatus(ImportProgress.STATUS_STOPPED));
        chat(QueryMessages.importStopped(progress.processed()));
    }

    void progress(Component message) {
        if (!silenced.get()) {
            chat(message);
        }
    }

    void chat(Component message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
        });
    }
}
