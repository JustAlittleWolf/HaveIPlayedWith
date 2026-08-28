package me.wolfii.command;

import java.util.Locale;

public final class DurationFormat {
	private DurationFormat() {
	}

	public static String compact(long minutes) {
		if (minutes < 60) {
			return minutes == 1 ? "1 minute" : minutes + " minutes";
		}
		double hours = minutes / 60.0;
		if (hours <= 99.0) {
			return String.format(Locale.ROOT, "%.1f hours", hours);
		}
		double days = hours / 24.0;
		return String.format(Locale.ROOT, "%.1f days", days);
	}

	public static String hover(long minutes) {
		long days = minutes / (24 * 60);
		long remainder = minutes % (24 * 60);
		long hours = remainder / 60;
		long mins = remainder % 60;
		if (days == 0) {
			return hours + ":" + String.format(Locale.ROOT, "%02d", mins);
		}
		String dayLabel = days == 1 ? "1 day" : days + " days";
		return dayLabel + ", " + String.format(Locale.ROOT, "%02d:%02d", hours, mins);
	}
}
