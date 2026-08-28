package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.chat.NoteMessages;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

final class PlayerLookup {
    private final PlayerStore players;
    private final MojangProfileApi mojang;
    private final ExecutorService worker;
    private final Supplier<String> liveSessionId;

    PlayerLookup(PlayerStore players, MojangProfileApi mojang, ExecutorService worker, Supplier<String> liveSessionId) {
        this.players = players;
        this.mojang = mojang;
        this.worker = worker;
        this.liveSessionId = liveSessionId;
    }

    void query(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        worker.execute(() -> {
            if (target.uuid() != null) {
                PlayerSnapshot match = players.get(target.uuid()).orElse(null);
                if (match == null) {
                    String name = target.name() != null ? target.name() : target.uuid().toString();
                    CommandFeedback.tell(source, QueryMessages.notPlayedWith(name, target.uuid()));
                    return;
                }
                show(source, match);
                return;
            }
            List<PlayerSnapshot> matches = players.findByName(target.name());
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
                players.applyMojangUsername(match.uuid(), profile.username(), java.time.Instant.now());
            }
        });
        PlayerSnapshot latest = players.get(match.uuid()).orElse(match);
        long currentSessionMinutes = players.sessionMinutes(latest.uuid(), liveSessionId.get());
        if (latest.hasPlayedBefore(currentSessionMinutes)) {
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
        latest.note().ifPresent(note -> CommandFeedback.tell(source, NoteMessages.note(latest)));
    }
}
