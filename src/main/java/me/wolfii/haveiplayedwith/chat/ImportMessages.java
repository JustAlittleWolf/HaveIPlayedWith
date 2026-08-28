package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;

public final class ImportMessages {
    private ImportMessages() {
    }

    public static Component notRunning() {
        return ChatStyle.wording("haveiplayedwith.import.not_running");
    }

    public static Component stopping() {
        return ChatStyle.wording("haveiplayedwith.import.stopping");
    }

    public static Component silenced() {
        return ChatStyle.wording("haveiplayedwith.import.silenced");
    }

    public static Component unsilenced() {
        return ChatStyle.wording("haveiplayedwith.import.unsilenced");
    }

    public static Component stillRunning(long processed, long total) {
        if (total > 0) {
            return ChatStyle.wording(
                "haveiplayedwith.import.still_running.counts",
                ChatStyle.count(processed),
                ChatStyle.count(total)
            );
        }
        return ChatStyle.wording("haveiplayedwith.import.still_running.messages", ChatStyle.count(processed));
    }

    public static Component stopped(long processed) {
        return ChatStyle.wording("haveiplayedwith.import.stopped", ChatStyle.count(processed));
    }

    public static Component resuming() {
        return ChatStyle.wording("haveiplayedwith.import.resuming");
    }

    public static Component alreadyRunning() {
        return ChatStyle.wording("haveiplayedwith.import.already_running");
    }

    public static Component starting() {
        return ChatStyle.wording("haveiplayedwith.import.starting");
    }

    public static Component notReady() {
        return ChatStyle.wording("haveiplayedwith.import.not_ready");
    }

    public static Component finished(long processed) {
        return ChatStyle.wording("haveiplayedwith.import.finished", ChatStyle.count(processed));
    }

    public static Component failed() {
        return ChatStyle.wording("haveiplayedwith.import.failed");
    }

    public static Component progress(long processed, long total) {
        int percent = (int) Math.min(100, (processed * 100) / total);
        return ChatStyle.wording(
            "haveiplayedwith.import.progress.counts",
            ChatStyle.count(processed),
            ChatStyle.count(total),
            ChatStyle.count(percent)
        );
    }

    public static Component progressMessages(long processed) {
        return ChatStyle.wording("haveiplayedwith.import.progress.messages", ChatStyle.count(processed));
    }
}
