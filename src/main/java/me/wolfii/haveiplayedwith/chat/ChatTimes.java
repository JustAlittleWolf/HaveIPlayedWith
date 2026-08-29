package me.wolfii.haveiplayedwith.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Timestamps shown in chat, rendered in the player's own time zone. */
final class ChatTimes {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private ChatTimes() {
    }

    static String dateTime(Instant instant) {
        return DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    static String date(LocalDate day) {
        return DATE.format(day);
    }
}
