package me.wolfii.haveiplayedwith.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.wolfii.haveiplayedwith.importing.ImportControls;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

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
            .then(ClientCommands.argument("player", PlayerTargetArgument.player())
                .executes(context -> {
                    lookup.query(context.getSource(), PlayerTargetArgument.get(context, "player"));
                    return 1;
                })));
        dispatcher.register(ClientCommands.literal("playernote")
            .then(ClientCommands.literal("confirm")
                .executes(context -> {
                    notes.confirmPending(context.getSource());
                    return 1;
                }))
            .then(ClientCommands.argument("player", PlayerTargetArgument.player())
                .then(ClientCommands.argument("note", StringArgumentType.greedyString())
                    .executes(context -> {
                        notes.setNote(context.getSource(), PlayerTargetArgument.get(context, "player"),
                            StringArgumentType.getString(context, "note"));
                        return 1;
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
