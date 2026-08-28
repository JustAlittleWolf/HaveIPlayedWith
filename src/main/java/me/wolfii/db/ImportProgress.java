package me.wolfii.db;

import java.time.LocalDateTime;

public record ImportProgress(
	String source,
	long processed,
	long total,
	LocalDateTime lastTimestamp,
	long skip,
	String status,
	boolean silenced
) {
	public static final String SOURCE_ALLTHELOGS = "allthelogs";
	public static final String STATUS_RUNNING = "running";
	public static final String STATUS_STOPPED = "stopped";
	public static final String STATUS_DONE = "done";

	public ImportProgress withStatus(String status) {
		return new ImportProgress(source, processed, total, lastTimestamp, skip, status, silenced);
	}

	public ImportProgress withSilenced(boolean silenced) {
		return new ImportProgress(source, processed, total, lastTimestamp, skip, status, silenced);
	}

	public ImportProgress withCursor(long processed, LocalDateTime lastTimestamp, long skip) {
		return new ImportProgress(source, processed, total, lastTimestamp, skip, status, silenced);
	}
}
