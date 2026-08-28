package me.wolfii.haveiplayedwith;

import me.wolfii.haveiplayedwith.command.Commands;
import me.wolfii.haveiplayedwith.importing.AllTheLogsCompat;
import me.wolfii.haveiplayedwith.importing.ImportControls;
import me.wolfii.haveiplayedwith.net.CraftyClient;
import me.wolfii.haveiplayedwith.net.MojangClient;
import me.wolfii.haveiplayedwith.observe.PlayerObserver;
import me.wolfii.haveiplayedwith.store.PlayerDatabase;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

public class HaveIPlayedWith implements ClientModInitializer {
    public static final String MOD_ID = "haveiplayedwith";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PlayerDatabase database;
    private PlayerObserver observer;

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(ModPaths.directory());
            database = new PlayerDatabase(ModPaths.databaseFile());
            MojangClient mojang = new MojangClient(database);
            CraftyClient crafty = new CraftyClient(database);
            observer = new PlayerObserver(database, mojang);
            observer.register();
            ImportControls imports = new ImportControls(database);
            if (FabricLoader.getInstance().isModLoaded("allthelogs")) {
                AllTheLogsCompat.install(database, crafty, imports);
            }
            new Commands(database, mojang, imports).register();
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
            LOGGER.info("Have I Played With initialized ({})", ModPaths.databaseFile());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Have I Played With", e);
        }
    }

    private void close() {
        if (observer != null) {
            observer.close();
        }
        if (database != null) {
            database.close();
        }
    }
}
