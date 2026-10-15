package me.wolfii.haveiplayedwith;

import me.wolfii.haveiplayedwith.command.HaveIPlayedWithCommands;
import me.wolfii.haveiplayedwith.observe.TickHandler;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class HaveIPlayedWith implements ClientModInitializer {
    public static final String MOD_ID = "haveiplayedwith";

    private PlayerStore players;
    private TickHandler tickHandler;

    @Override
    public void onInitializeClient() {
        try {
            players = new PlayerStore(ModPaths.databaseFile());
            ProfileApi profiles = new ProfileApi(players.profiles());
            tickHandler = new TickHandler(players, profiles);
            tickHandler.register();
            new HaveIPlayedWithCommands(players, profiles, tickHandler).register();
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
            ModLog.LOGGER.info("Have I Played With initialized ({})", ModPaths.databaseFile());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Have I Played With", e);
        }
    }

    private void close() {
        if (tickHandler != null) {
            tickHandler.close();
        }
        if (players != null) {
            players.close();
        }
    }
}
