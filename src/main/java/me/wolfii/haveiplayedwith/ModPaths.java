package me.wolfii.haveiplayedwith;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Instance-relative paths. The store file is {@code haveiplayedwith/store.db}
 * under Fabric's config directory.
 */
public final class ModPaths {
    private static final String DATABASE_FILE_NAME = "store.db";

    private ModPaths() {
    }

    private static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve(HaveIPlayedWith.MOD_ID);
    }

    public static Path databaseFile() {
        return directory().resolve(DATABASE_FILE_NAME);
    }
}
