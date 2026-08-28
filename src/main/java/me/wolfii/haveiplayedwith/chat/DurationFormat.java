package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class DurationFormat {
    private DurationFormat() {
    }

    public static Component compact(long minutes) {
        if (minutes < 60) {
            return ChatStyle.wording(
                minutes == 1 ? "haveiplayedwith.duration.minute" : "haveiplayedwith.duration.minutes",
                ChatStyle.duration(minutes)
            );
        }
        double hours = minutes / 60.0;
        if (hours <= 99.0) {
            return ChatStyle.wording("haveiplayedwith.duration.hours", ChatStyle.duration(formatTenths(hours)));
        }
        double days = hours / 24.0;
        return ChatStyle.wording("haveiplayedwith.duration.days", ChatStyle.duration(formatTenths(days)));
    }

    public static Component hover(long minutes) {
        long days = minutes / (24 * 60);
        long remainder = minutes % (24 * 60);
        long hours = remainder / 60;
        long mins = remainder % 60;
        if (days == 0) {
            return ChatStyle.wording(
                "haveiplayedwith.duration.hover",
                ChatStyle.duration(hours),
                ChatStyle.duration(String.format(Locale.ROOT, "%02d", mins))
            );
        }
        Component dayLabel = ChatStyle.wording(
            days == 1 ? "haveiplayedwith.duration.day" : "haveiplayedwith.duration.days.count",
            ChatStyle.duration(days)
        );
        return ChatStyle.wording(
            "haveiplayedwith.duration.hover.with_days",
            dayLabel,
            ChatStyle.duration(String.format(Locale.ROOT, "%02d:%02d", hours, mins))
        );
    }

    private static String formatTenths(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
