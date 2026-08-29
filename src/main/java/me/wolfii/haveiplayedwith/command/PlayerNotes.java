package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.chat.NoteMessages;
import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.concurrent.ExecutorService;

final class PlayerNotes {
    private static final String CLEAR = "clear";

    private final PlayerStore players;
    private final ExecutorService worker;

    PlayerNotes(PlayerStore players, ExecutorService worker) {
        this.players = players;
        this.worker = worker;
    }

    void show(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        worker.execute(() -> {
            List<PlayerSnapshot> matches = matches(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (PlayerSnapshot match : matches) {
                if (match.note().isPresent()) {
                    CommandFeedback.tell(source, NoteMessages.note(match));
                } else {
                    CommandFeedback.tell(source, NoteMessages.noteMissing(match.currentUsername()));
                }
            }
        });
    }

    void write(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target, String note) {
        String cleaned = note.replace('\n', ' ').replace('\r', ' ').strip();
        if (cleaned.isEmpty() || CLEAR.equalsIgnoreCase(cleaned)) {
            clear(source, target);
            return;
        }
        worker.execute(() -> {
            List<PlayerSnapshot> matches = matches(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (PlayerSnapshot match : matches) {
                players.setNote(match.uuid(), match.currentUsername(), cleaned);
                CommandFeedback.tell(source, NoteMessages.noteSaved(match.currentUsername()));
            }
        });
    }

    private void clear(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        worker.execute(() -> {
            List<PlayerSnapshot> matches = matches(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (PlayerSnapshot match : matches) {
                players.setNote(match.uuid(), match.currentUsername(), "");
                CommandFeedback.tell(source, NoteMessages.noteCleared(match.currentUsername()));
            }
        });
    }

    private List<PlayerSnapshot> matches(PlayerArguments.ResolvedPlayer target) {
        if (target.uuid() != null) {
            return players.get(target.uuid()).map(List::of).orElseGet(List::of);
        }
        return players.findByName(target.name());
    }

    private static void tellUnknown(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        if (target.uuid() != null) {
            String name = target.name() != null ? target.name() : target.uuid().toString();
            CommandFeedback.tell(source, QueryMessages.notPlayedWith(name, target.uuid()));
            return;
        }
        CommandFeedback.tell(source, QueryMessages.notPlayedWith(target.name()));
    }
}
