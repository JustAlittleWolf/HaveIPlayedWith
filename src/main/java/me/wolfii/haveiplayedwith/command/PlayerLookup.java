package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.net.MojangClient;
import me.wolfii.haveiplayedwith.store.PlayerDatabase;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.concurrent.ExecutorService;

final class PlayerLookup {
    private final PlayerDatabase database;
    private final MojangClient mojang;
    private final ExecutorService worker;

    PlayerLookup(PlayerDatabase database, MojangClient mojang, ExecutorService worker) {
        this.database = database;
        this.mojang = mojang;
        this.worker = worker;
    }

    void query(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        worker.execute(() -> {
            if (target.uuid() != null) {
                PlayerSnapshot match = database.get(target.uuid()).orElse(null);
                if (match == null) {
                    String name = target.name() != null ? target.name() : target.uuid().toString();
                    CommandFeedback.tell(source, QueryMessages.notPlayedWith(name, target.uuid()));
                    return;
                }
                show(source, match);
                return;
            }
            List<PlayerSnapshot> matches = database.findByName(target.name());
            if (matches.isEmpty()) {
                CommandFeedback.tell(source, QueryMessages.notPlayedWith(target.name()));
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
            CommandFeedback.tell(source, QueryMessages.playedWith(latest));
            if (!latest.servers().isEmpty()) {
                CommandFeedback.tell(source, QueryMessages.seenOn(latest));
            }
            if (!latest.pastNames().isEmpty()) {
                CommandFeedback.tell(source, QueryMessages.pastNames(latest));
            }
        } else {
            CommandFeedback.tell(source, QueryMessages.notPlayedWith(latest.currentUsername(), latest.uuid()));
        }
        latest.note().ifPresent(note -> CommandFeedback.tell(source, QueryMessages.note(latest)));
    }
}
