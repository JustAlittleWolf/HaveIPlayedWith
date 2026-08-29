package me.wolfii.haveiplayedwith;

import me.wolfii.haveiplayedwith.command.HaveIPlayedWithCommands;
import me.wolfii.haveiplayedwith.observe.PlayerObserver;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class HaveIPlayedWith implements ClientModInitializer {
    public static final String MOD_ID = "haveiplayedwith";

    private PlayerStore players;
    private PlayerObserver observer;

    @Override
    public void onInitializeClient() {
        try {
            players = new PlayerStore(ModPaths.databaseFile());
            ProfileApi profiles = new ProfileApi(players.profiles());
            observer = new PlayerObserver(players, profiles);
            observer.register();
            new HaveIPlayedWithCommands(players, profiles, observer).register();
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
            ModLog.LOGGER.info("Have I Played With initialized ({})", ModPaths.databaseFile());
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
