package me.wolfii.importing;

import com.mojang.brigadier.CommandDispatcher;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.net.CraftyClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Isolated so AllTheLogs classes are only loaded when that mod is present.
 */
public final class AllTheLogsCompat {
	private AllTheLogsCompat() {
	}

	public static void install(PlayerDatabase database, CraftyClient crafty) {
		AllTheLogsImporter importer = new AllTheLogsImporter(database, crafty);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher, importer));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> importer.notifyIfRunning());
		importer.resumeIfNeeded();
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, AllTheLogsImporter importer) {
		dispatcher.register(ClientCommands.literal("importhaveiplayedwith")
			.then(ClientCommands.literal("allthelogs").executes(context -> {
				importer.startFromCommand();
				return 1;
			}))
			.then(ClientCommands.literal("silence").executes(context -> {
				importer.toggleSilenceFromCommand();
				return 1;
			}))
			.then(ClientCommands.literal("stop").executes(context -> {
				importer.stopFromCommand();
				return 1;
			})));
	}
}
