package me.wolfii.db;

import java.time.LocalDateTime;

public record ImportProgress(
	String source,
	long processed,
	long total,
	LocalDateTime lastTimestamp,
	long skip,
	String status
) {
	public static final String SOURCE_ALLTHELOGS = "allthelogs";
	public static final String STATUS_IDLE = "idle";
	public static final String STATUS_RUNNING = "running";
	public static final String STATUS_DONE = "done";
}
