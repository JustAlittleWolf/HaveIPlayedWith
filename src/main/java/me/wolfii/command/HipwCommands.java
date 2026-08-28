package me.wolfii.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.wolfii.clientdatacommandselector.ClientEntityArgument;
import me.wolfii.clientdatacommandselector.ClientEntitySelector;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.db.PlayerSnapshot;
import me.wolfii.importing.ImportControls;
import me.wolfii.net.MojangClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class HipwCommands {
    private final PlayerDatabase database;
    private final MojangClient mojang;
    private final ImportControls imports;
    private final AtomicReference<PendingNote> pendingNote = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "haveiplayedwith-commands");
        thread.setDaemon(true);
        return thread;
    });

    public HipwCommands(PlayerDatabase database, MojangClient mojang, ImportControls imports) {
        this.database = database;
        this.mojang = mojang;
        this.imports = imports;
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

    static void tell(FabricClientCommandSource source, Component message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(message));
    }

    private static List<ResolvedPlayer> resolvePlayers(CommandContext<FabricClientCommandSource> context, String arg) throws CommandSyntaxException {
        List<? extends Player> found = ClientEntityArgument.getPlayers(context, arg);
        if (!found.isEmpty()) {
            List<ResolvedPlayer> resolved = new ArrayList<>(found.size());
            for (Player player : found) {
                resolved.add(new ResolvedPlayer(player.getGameProfile().name(), player.getGameProfile().id()));
            }
            return resolved;
        }
        ClientEntitySelector selector = (ClientEntitySelector) context.getArgument(arg, EntitySelector.class);
        String name = selector.clientdatacommandupdated$playerName();
        UUID uuid = selector.clientdatacommandupdated$entityUUID();
        if (name != null || uuid != null) {
            return List.of(new ResolvedPlayer(name, uuid));
        }
        return List.of();
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
    }

    private void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("haveiplayedwith")
            .then(ClientCommands.argument("player", ClientEntityArgument.players())
                .executes(context -> {
                    List<ResolvedPlayer> targets = resolvePlayers(context, "player");
                    if (targets.isEmpty()) {
                        tell(context.getSource(), QueryMessages.noMatchingPlayers());
                        return 0;
                    }
                    for (ResolvedPlayer target : targets) {
                        query(context.getSource(), target);
                    }
                    return targets.size();
                })));
        dispatcher.register(ClientCommands.literal("playernote")
            .then(ClientCommands.literal("confirm")
                .executes(context -> {
                    confirmPending(context.getSource());
                    return 1;
                }))
            .then(ClientCommands.argument("player", ClientEntityArgument.players())
                .then(ClientCommands.argument("note", StringArgumentType.greedyString())
                    .executes(context -> {
                        List<ResolvedPlayer> targets = resolvePlayers(context, "player");
                        if (targets.isEmpty()) {
                            tell(context.getSource(), QueryMessages.noMatchingPlayers());
                            return 0;
                        }
                        String note = StringArgumentType.getString(context, "note");
                        for (ResolvedPlayer target : targets) {
                            setNote(context.getSource(), target, note);
                        }
                        return targets.size();
                    }))));
        var importRoot = ClientCommands.literal("importhaveiplayedwith")
            .then(ClientCommands.literal("silence").executes(context -> {
                imports.toggleSilenceFromCommand();
                return 1;
            }))
            .then(ClientCommands.literal("stop").executes(context -> {
                imports.stopFromCommand();
                return 1;
            }));
        if (imports.hasAllTheLogs()) {
            importRoot = importRoot.then(ClientCommands.literal("allthelogs").executes(context -> {
                imports.startAllTheLogs();
                return 1;
            }));
        }
        dispatcher.register(importRoot);
    }

    private void query(FabricClientCommandSource source, ResolvedPlayer target) {
        worker.execute(() -> {
            if (target.uuid() != null) {
                PlayerSnapshot match = database.get(target.uuid()).orElse(null);
                if (match == null) {
                    String name = target.name() != null ? target.name() : target.uuid().toString();
                    tell(source, QueryMessages.notPlayedWith(name, target.uuid()));
                    return;
                }
                show(source, match);
                return;
            }
            List<PlayerSnapshot> matches = database.findByName(target.name());
            if (matches.isEmpty()) {
                tell(source, QueryMessages.notPlayedWith(target.name()));
                return;
            }
            for (PlayerSnapshot match : matches) {
                show(source, match);
            }
        });
    }

    private void show(FabricClientCommandSource source, PlayerSnapshot match) {
        mojang.lookupUuid(match.uuid()).ifPresent(profile -> {
            if (!profile.username().equals(match.currentUsername())) {
                database.applyMojangUsername(match.uuid(), profile.username(), java.time.Instant.now());
            }
        });
        PlayerSnapshot latest = database.get(match.uuid()).orElse(match);
        if (latest.hasPlayed()) {
            tell(source, QueryMessages.playedWith(latest));
            if (!latest.servers().isEmpty()) {
                tell(source, QueryMessages.seenOn(latest));
            }
            if (!latest.pastNames().isEmpty()) {
                tell(source, QueryMessages.pastNames(latest));
            }
        } else {
            tell(source, QueryMessages.notPlayedWith(latest.currentUsername(), latest.uuid()));
        }
        latest.note().ifPresent(note -> tell(source, QueryMessages.note(note)));
    }

    private void setNote(FabricClientCommandSource source, ResolvedPlayer target, String note) {
        String cleaned = note.replace('\n', ' ').replace('\r', ' ').strip();
        worker.execute(() -> {
            List<PlayerSnapshot> matches = target.uuid() != null
                ? database.get(target.uuid()).map(List::of).orElseGet(List::of)
                : database.findByName(target.name());
            if (!matches.isEmpty()) {
                for (PlayerSnapshot match : matches) {
                    database.setNote(match.uuid(), match.currentUsername(), cleaned);
                    tell(source, QueryMessages.noteSaved(match.currentUsername()));
                }
                return;
            }
            String name = target.name();
            UUID uuid = target.uuid();
            if (uuid == null && name != null) {
                uuid = uuidFromTab(name);
                if (uuid == null) {
                    uuid = mojang.lookupName(name).map(MojangClient.Profile::uuid).orElse(null);
                }
            }
            if (uuid == null) {
                tell(source, QueryMessages.unknownAccount(name));
                return;
            }
            String username = name != null ? name : uuid.toString();
            pendingNote.set(new PendingNote(uuid, username, cleaned));
            tell(source, QueryMessages.noteConfirm(username, uuid));
        });
    }

    private void confirmPending(FabricClientCommandSource source) {
        PendingNote pending = pendingNote.getAndSet(null);
        if (pending == null) {
            tell(source, QueryMessages.nothingToConfirm());
            return;
        }
        worker.execute(() -> {
            database.setNote(pending.uuid(), pending.username(), pending.note());
            tell(source, QueryMessages.noteSaved(pending.username()));
        });
    }

    private record ResolvedPlayer(String name, UUID uuid) {
    }

    private record PendingNote(UUID uuid, String username, String note) {
    }
}
