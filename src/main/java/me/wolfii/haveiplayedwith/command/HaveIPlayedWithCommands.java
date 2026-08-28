package me.wolfii.haveiplayedwith.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.wolfii.clientdatacommandselector.ClientEntityArgument;
import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.importing.ImportControls;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HaveIPlayedWithCommands {
    private final ImportControls imports;
    private final PlayerLookup lookup;
    private final PlayerNotes notes;

    public HaveIPlayedWithCommands(PlayerStore players, MojangProfileApi mojang, ImportControls imports) {
        this.imports = imports;
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "haveiplayedwith-commands");
            thread.setDaemon(true);
            return thread;
        });
        this.lookup = new PlayerLookup(players, mojang, worker);
        this.notes = new PlayerNotes(players, mojang, worker);
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
    }

    private void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("haveiplayedwith")
            .then(ClientCommands.argument("player", ClientEntityArgument.playerNameOrUuid())
                .executes(context -> {
                    PlayerArguments.ResolvedPlayer target = PlayerArguments.resolvePlayer(context, "player");
                    if (target == null) {
                        CommandFeedback.tell(context.getSource(), QueryMessages.noMatchingPlayers());
                        return 0;
                    }
                    lookup.query(context.getSource(), target);
                    return 1;
                })));
        dispatcher.register(ClientCommands.literal("playernote")
            .then(ClientCommands.literal("confirm")
                .executes(context -> {
                    notes.confirmPending(context.getSource());
                    return 1;
                }))
            .then(ClientCommands.argument("player", ClientEntityArgument.players())
                .then(ClientCommands.argument("note", StringArgumentType.greedyString())
                    .executes(context -> {
                        List<PlayerArguments.ResolvedPlayer> targets = PlayerArguments.resolvePlayers(context, "player");
                        if (targets.isEmpty()) {
                            CommandFeedback.tell(context.getSource(), QueryMessages.noMatchingPlayers());
                            return 0;
                        }
                        String note = StringArgumentType.getString(context, "note");
                        for (PlayerArguments.ResolvedPlayer target : targets) {
                            notes.setNote(context.getSource(), target, note);
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
}
