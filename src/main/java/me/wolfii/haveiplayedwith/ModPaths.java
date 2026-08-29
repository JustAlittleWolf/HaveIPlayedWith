package me.wolfii.haveiplayedwith;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Instance-relative paths. The Nitrite file lives in Fabric's config directory
 * under {@code haveiplayedwith/database/store.db}.
 */
public final class ModPaths {
    private static final String DATABASE_DIRECTORY_NAME = "database";

    private ModPaths() {
    }

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve(HaveIPlayedWith.MOD_ID);
    }

    public static Path databaseDirectory() {
        return directory().resolve(DATABASE_DIRECTORY_NAME);
    }
}
