package me.wolfii.haveiplayedwith;

import me.wolfii.haveiplayedwith.command.HaveIPlayedWithCommands;
import me.wolfii.haveiplayedwith.crafty.CraftyPlayerApi;
import me.wolfii.haveiplayedwith.importing.AllTheLogsCompat;
import me.wolfii.haveiplayedwith.importing.ImportControls;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.observe.PlayerObserver;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HaveIPlayedWith implements ClientModInitializer {
    public static final String MOD_ID = "haveiplayedwith";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PlayerStore players;
    private PlayerObserver observer;

    @Override
    public void onInitializeClient() {
        try {
            players = new PlayerStore(ModPaths.databaseDirectory());
            MojangProfileApi mojang = new MojangProfileApi(players.mojangProfiles());
            CraftyPlayerApi crafty = new CraftyPlayerApi();
            observer = new PlayerObserver(players, mojang);
            observer.register();
            ImportControls imports = new ImportControls(players.importProgress());
            if (FabricLoader.getInstance().isModLoaded("allthelogs")) {
                AllTheLogsCompat.install(players, crafty, mojang, imports);
            }
            new HaveIPlayedWithCommands(players, mojang, imports, observer).register();
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
            LOGGER.info("Have I Played With initialized ({})", ModPaths.databaseDirectory());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Have I Played With", e);
        }
    }

    private void close() {
        if (observer != null) {
            observer.close();
        }
        if (players != null) {
            players.close();
        }
    }
}
