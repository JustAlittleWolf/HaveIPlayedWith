package me.wolfii.allthelogs.api;

import java.nio.file.Path;

public interface LogSource {
	interface File extends LogSource {
		Path path();
	}

	interface Archive extends LogSource {
		Path path();

		String entryPath();
	}

	interface Session extends LogSource {
		String id();
	}
}
