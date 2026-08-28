package me.wolfii;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Instance-relative paths. The database lives in {@code .config/haveiplayedwith}.
 */
public final class HipwPaths {
	public static final String DATABASE_FILE_NAME = "players.mv";

	private HipwPaths() {
	}

	public static Path gameDirectory() {
		return FabricLoader.getInstance().getGameDir();
	}

	public static Path directory() {
		return gameDirectory().resolve(".config").resolve("haveiplayedwith");
	}

	public static Path databaseFile() {
		return directory().resolve(DATABASE_FILE_NAME);
	}
}
