package me.wolfii.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.db.PlayerSnapshot;
import me.wolfii.net.MojangClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class HipwCommands {
	private static final SuggestionProvider<FabricClientCommandSource> TAB_PLAYERS =
		(context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

	private final PlayerDatabase database;
	private final MojangClient mojang;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-commands");
		thread.setDaemon(true);
		return thread;
	});

	public HipwCommands(PlayerDatabase database, MojangClient mojang) {
		this.database = database;
		this.mojang = mojang;
	}

	public void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
	}

	private void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("haveiplayedwith")
			.then(ClientCommands.argument("name", StringArgumentType.word())
				.suggests(TAB_PLAYERS)
				.executes(context -> {
					String name = StringArgumentType.getString(context, "name");
					query(context.getSource(), name);
					return 1;
				})));
		dispatcher.register(ClientCommands.literal("playernote")
			.then(ClientCommands.literal("confirm")
				.then(ClientCommands.argument("rest", StringArgumentType.greedyString())
					.executes(context -> {
						handleConfirm(context.getSource(), StringArgumentType.getString(context, "rest"));
						return 1;
					})))
			.then(ClientCommands.argument("name", StringArgumentType.word())
				.suggests(TAB_PLAYERS)
				.then(ClientCommands.argument("note", StringArgumentType.greedyString())
					.executes(context -> {
						setNote(context.getSource(), StringArgumentType.getString(context, "name"),
							StringArgumentType.getString(context, "note"));
						return 1;
					}))));
	}

	private void query(FabricClientCommandSource source, String name) {
		worker.execute(() -> {
			List<PlayerSnapshot> matches = database.findByName(name);
			if (matches.isEmpty()) {
				tell(source, QueryMessages.notPlayedWith(name));
				return;
			}
			for (PlayerSnapshot match : matches) {
				mojang.lookupUuid(match.uuid()).ifPresent(profile -> {
					if (!profile.username().equals(match.currentUsername())) {
						database.applyMojangUsername(match.uuid(), profile.username(), java.time.Instant.now());
					}
				});
				PlayerSnapshot latest = database.get(match.uuid()).orElse(match);
				if (latest.hasPlayed()) {
					tell(source, QueryMessages.playedWith(latest));
					if (!latest.pastNames().isEmpty()) {
						tell(source, QueryMessages.pastNames(latest));
					}
				} else {
					tell(source, QueryMessages.notPlayedWith(latest.currentUsername(), latest.uuid()));
				}
				latest.note().ifPresent(note -> tell(source, QueryMessages.note(note)));
			}
		});
	}

	private void setNote(FabricClientCommandSource source, String name, String note) {
		String cleaned = note.replace('\n', ' ').replace('\r', ' ').strip();
		worker.execute(() -> {
			List<PlayerSnapshot> matches = database.findByName(name);
			if (!matches.isEmpty()) {
				for (PlayerSnapshot match : matches) {
					database.setNote(match.uuid(), match.currentUsername(), cleaned);
					tell(source, QueryMessages.noteSaved(match.currentUsername()));
				}
				return;
			}
			UUID uuid = uuidFromTab(name);
			if (uuid == null) {
				uuid = mojang.lookupName(name).map(MojangClient.Profile::uuid).orElse(null);
			}
			if (uuid == null) {
				tell(source, QueryMessages.unknownAccount(name));
				return;
			}
			UUID confirmed = uuid;
			tell(source, QueryMessages.noteConfirm(name, confirmed, cleaned));
		});
	}

	private void handleConfirm(FabricClientCommandSource source, String rest) {
		String cleaned = rest.replace('\n', ' ').replace('\r', ' ').strip();
		int space = cleaned.indexOf(' ');
		if (space > 0) {
			String first = cleaned.substring(0, space);
			String remainder = cleaned.substring(space + 1).strip();
			if (isUuid(first) && !remainder.isEmpty()) {
				confirmNote(source, first, remainder);
				return;
			}
		}
		setNote(source, "confirm", cleaned);
	}

	private void confirmNote(FabricClientCommandSource source, String uuidText, String note) {
		worker.execute(() -> {
			UUID uuid;
			try {
				uuid = UUID.fromString(uuidText);
			} catch (IllegalArgumentException e) {
				tell(source, QueryMessages.unknownAccount(uuidText));
				return;
			}
			String username = database.get(uuid).map(PlayerSnapshot::currentUsername).orElse(null);
			if (username == null) {
				username = mojang.lookupUuid(uuid).map(MojangClient.Profile::username).orElse(uuidText);
			}
			database.setNote(uuid, username, note.replace('\n', ' ').replace('\r', ' ').strip());
			tell(source, QueryMessages.noteSaved(username));
		});
	}

	private static UUID uuidFromTab(String name) {
		Minecraft client = Minecraft.getInstance();
		AtomicReference<UUID> found = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		client.execute(() -> {
			try {
				ClientPacketListener connection = client.getConnection();
				if (connection != null) {
					for (PlayerInfo info : connection.getListedOnlinePlayers()) {
						if (name.equalsIgnoreCase(info.getProfile().name())) {
							found.set(info.getProfile().id());
							break;
						}
					}
				}
			} finally {
				latch.countDown();
			}
		});
		try {
			latch.await(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return found.get();
	}

	private static boolean isUuid(String text) {
		try {
			UUID.fromString(text);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	static void tell(FabricClientCommandSource source, Component message) {
		Minecraft.getInstance().execute(() -> source.sendFeedback(message));
	}
}
