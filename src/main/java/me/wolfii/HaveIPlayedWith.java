package me.wolfii;

import me.wolfii.command.HipwCommands;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.importing.AllTheLogsCompat;
import me.wolfii.net.CraftyClient;
import me.wolfii.net.MojangClient;
import me.wolfii.observe.PlayerObserver;
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
			Files.createDirectories(HipwPaths.directory());
			database = new PlayerDatabase(HipwPaths.databaseFile());
			MojangClient mojang = new MojangClient(database);
			CraftyClient crafty = new CraftyClient(database);
			observer = new PlayerObserver(database, mojang);
			observer.register();
			new HipwCommands(database, mojang).register();
			if (FabricLoader.getInstance().isModLoaded("allthelogs")) {
				AllTheLogsCompat.install(database, crafty);
			}
			ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
			LOGGER.info("Have I Played With initialized ({})", HipwPaths.databaseFile());
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
