package me.wolfii.haveiplayedwith.importing;

import me.wolfii.haveiplayedwith.chat.ImportMessages;
import me.wolfii.haveiplayedwith.store.ImportProgress;
import me.wolfii.haveiplayedwith.store.ImportProgressStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Import start/stop/silence state that does not touch AllTheLogs classes, so the
 * silence and stop commands can be registered even when that mod is absent.
 */
public final class ImportControls {
    private final ImportProgressStore progressStore;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean silenced = new AtomicBoolean(false);
    private volatile ImportProgress latest = new ImportProgress(
        ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportProgress.STATUS_STOPPED, false
    );
    private volatile Runnable startAllTheLogs;

    public ImportControls(ImportProgressStore progressStore) {
        this.progressStore = progressStore;
        progressStore.get(ImportProgress.SOURCE_ALLTHELOGS).ifPresent(stored -> {
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
            chat(ImportMessages.notRunning());
            return;
        }
        stopRequested.set(true);
        chat(ImportMessages.stopping());
    }

    public void toggleSilenceFromCommand() {
        boolean next = !silenced.get();
        silenced.set(next);
        ImportProgress stored = scheduled.get()
            ? latest
            : progressStore.get(ImportProgress.SOURCE_ALLTHELOGS).orElse(latest);
        save(stored.withSilenced(next));
        chat(next ? ImportMessages.silenced() : ImportMessages.unsilenced());
    }

    public void notifyIfRunning() {
        if (!scheduled.get()) {
            return;
        }
        progress(ImportMessages.stillRunning(latest.processed(), latest.total()));
    }

    void save(ImportProgress progress) {
        ImportProgress stored = progress.withSilenced(silenced.get());
        latest = stored;
        progressStore.save(stored);
    }

    void saveStopped(ImportProgress progress) {
        save(progress.withStatus(ImportProgress.STATUS_STOPPED));
        chat(ImportMessages.stopped(progress.processed()));
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
