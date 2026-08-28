package me.wolfii.importing;

import me.wolfii.allthelogs.api.ChatLog;
import me.wolfii.allthelogs.api.LogSource;

public final class ChatLogIds {
    private ChatLogIds() {
    }

    public static String sessionId(ChatLog log) {
        LogSource source = log.source();
        if (source instanceof LogSource.File file) {
            return "atl:file:" + file.path().toAbsolutePath();
        }
        if (source instanceof LogSource.Archive archive) {
            return "atl:archive:" + archive.path().toAbsolutePath() + "!/" + archive.entryPath();
        }
        if (source instanceof LogSource.Session session) {
            String id = session.id();
            return "atl:session:" + (id == null ? log.startTime() : id);
        }
        return "atl:log:" + log.date() + ":" + log.startTime();
    }
}
