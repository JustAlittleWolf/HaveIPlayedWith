package me.wolfii.haveiplayedwith.importing;

import me.wolfii.haveiplayedwith.crafty.CraftyPlayerApi;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Isolated so AllTheLogs classes are only loaded when that mod is present.
 */
public final class AllTheLogsCompat {
    private AllTheLogsCompat() {
    }

    public static void install(PlayerStore players, CraftyPlayerApi crafty, MojangProfileApi mojang, ImportControls controls) {
        AllTheLogsImporter importer = new AllTheLogsImporter(players, crafty, mojang, controls);
        controls.setStartAllTheLogs(importer::startFromCommand);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> controls.notifyIfRunning());
        importer.resumeIfNeeded();
    }
}
