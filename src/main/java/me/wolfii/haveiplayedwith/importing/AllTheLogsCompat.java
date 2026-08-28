package me.wolfii.haveiplayedwith.importing;

import me.wolfii.haveiplayedwith.net.CraftyClient;
import me.wolfii.haveiplayedwith.store.PlayerDatabase;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Isolated so AllTheLogs classes are only loaded when that mod is present.
 */
public final class AllTheLogsCompat {
    private AllTheLogsCompat() {
    }

    public static void install(PlayerDatabase database, CraftyClient crafty, ImportControls controls) {
        AllTheLogsImporter importer = new AllTheLogsImporter(database, crafty, controls);
        controls.setStartAllTheLogs(importer::startFromCommand);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> controls.notifyIfRunning());
        importer.resumeIfNeeded();
    }
}
