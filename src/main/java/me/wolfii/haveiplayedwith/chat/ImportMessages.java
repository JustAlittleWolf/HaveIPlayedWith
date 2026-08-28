package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;

public final class ImportMessages {
    private ImportMessages() {
    }

    public static Component notRunning() {
        return ChatStyle.gray("haveiplayedwith.import.not_running");
    }

    public static Component stopping() {
        return ChatStyle.gray("haveiplayedwith.import.stopping");
    }

    public static Component silenced() {
        return ChatStyle.gray("haveiplayedwith.import.silenced");
    }

    public static Component unsilenced() {
        return ChatStyle.gray("haveiplayedwith.import.unsilenced");
    }

    public static Component stillRunning(long processed, long total) {
        if (total > 0) {
            return ChatStyle.gray("haveiplayedwith.import.still_running.counts", processed, total);
        }
        return ChatStyle.gray("haveiplayedwith.import.still_running.messages", processed);
    }

    public static Component stopped(long processed) {
        return ChatStyle.gray("haveiplayedwith.import.stopped", processed);
    }

    public static Component resuming() {
        return ChatStyle.gray("haveiplayedwith.import.resuming");
    }

    public static Component alreadyRunning() {
        return ChatStyle.gray("haveiplayedwith.import.already_running");
    }

    public static Component starting() {
        return ChatStyle.gray("haveiplayedwith.import.starting");
    }

    public static Component notReady() {
        return ChatStyle.gray("haveiplayedwith.import.not_ready");
    }

    public static Component finished(long processed) {
        return ChatStyle.gray("haveiplayedwith.import.finished", processed);
    }

    public static Component failed() {
        return ChatStyle.gray("haveiplayedwith.import.failed");
    }

    public static Component progress(long processed, long total) {
        int percent = (int) Math.min(100, (processed * 100) / total);
        return ChatStyle.gray("haveiplayedwith.import.progress.counts", processed, total, percent);
    }

    public static Component progressMessages(long processed) {
        return ChatStyle.gray("haveiplayedwith.import.progress.messages", processed);
    }
}
