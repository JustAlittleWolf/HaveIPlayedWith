package me.wolfii.haveiplayedwith;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Instance-relative paths. The database lives in Fabric's config directory
 * under {@code haveiplayedwith}.
 */
public final class ModPaths {
    public static final String DATABASE_FILE_NAME = "players.mv";

    private ModPaths() {
    }

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("haveiplayedwith");
    }

    public static Path databaseFile() {
        return directory().resolve(DATABASE_FILE_NAME);
    }
}
