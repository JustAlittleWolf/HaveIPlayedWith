package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class DurationFormat {
    private DurationFormat() {
    }

    public static Component compact(long minutes) {
        if (minutes < 60) {
            return minutes == 1
                ? Component.translatable("haveiplayedwith.duration.minute")
                : Component.translatable("haveiplayedwith.duration.minutes", minutes);
        }
        double hours = minutes / 60.0;
        if (hours <= 99.0) {
            return Component.translatable("haveiplayedwith.duration.hours", String.format(Locale.ROOT, "%.1f", hours));
        }
        double days = hours / 24.0;
        return Component.translatable("haveiplayedwith.duration.days", String.format(Locale.ROOT, "%.1f", days));
    }

    public static Component hover(long minutes) {
        long days = minutes / (24 * 60);
        long remainder = minutes % (24 * 60);
        long hours = remainder / 60;
        long mins = remainder % 60;
        if (days == 0) {
            return Component.translatable("haveiplayedwith.duration.hover", hours, String.format(Locale.ROOT, "%02d", mins));
        }
        Component dayLabel = days == 1
            ? Component.translatable("haveiplayedwith.duration.day")
            : Component.translatable("haveiplayedwith.duration.days.count", days);
        return Component.translatable("haveiplayedwith.duration.hover.with_days", dayLabel, String.format(Locale.ROOT, "%02d:%02d", hours, mins));
    }
}
