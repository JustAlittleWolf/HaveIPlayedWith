package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.chat.NoteMessages;
import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.mojang.MojangProfile;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

final class PlayerNotes {
    private static final String CLEAR = "clear";

    private final PlayerStore players;
    private final MojangProfileApi mojang;
    private final ExecutorService worker;

    PlayerNotes(PlayerStore players, MojangProfileApi mojang, ExecutorService worker) {
        this.players = players;
        this.mojang = mojang;
        this.worker = worker;
    }

    void show(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        worker.execute(() -> {
            List<Resolved> matches = resolve(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (Resolved match : matches) {
                PlayerSnapshot stored = match.stored().orElse(null);
                if (stored != null && stored.note().isPresent()) {
                    CommandFeedback.tell(source, NoteMessages.note(stored));
                } else {
                    CommandFeedback.tell(source, NoteMessages.noteMissing(match.username()));
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
            List<Resolved> matches = resolve(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (Resolved match : matches) {
                players.setNote(match.uuid(), match.username(), cleaned);
                CommandFeedback.tell(source, NoteMessages.noteSaved(match.username()));
            }
        });
    }

    private void clear(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        worker.execute(() -> {
            List<Resolved> matches = resolve(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (Resolved match : matches) {
                if (match.stored().isEmpty()) {
                    CommandFeedback.tell(source, NoteMessages.noteMissing(match.username()));
                    continue;
                }
                players.setNote(match.uuid(), match.username(), "");
                CommandFeedback.tell(source, NoteMessages.noteCleared(match.username()));
            }
        });
    }

    /**
     * Players already in the store, or a real Minecraft account resolved from tab / Mojang.
     * Made-up names that are not a Mojang account are rejected.
     */
    private List<Resolved> resolve(PlayerArguments.ResolvedPlayer target) {
        List<PlayerSnapshot> stored = storedMatches(target);
        if (!stored.isEmpty()) {
            return stored.stream()
                .map(match -> new Resolved(match.uuid(), match.currentUsername(), Optional.of(match)))
                .toList();
        }
        return minecraftAccount(target)
            .map(account -> new Resolved(account.uuid(), account.username(), players.get(account.uuid())))
            .map(List::of)
            .orElseGet(List::of);
    }

    private List<PlayerSnapshot> storedMatches(PlayerArguments.ResolvedPlayer target) {
        if (target.uuid() != null) {
            return players.get(target.uuid()).map(List::of).orElseGet(List::of);
        }
        return players.findByName(target.name());
    }

    private Optional<MojangProfile> minecraftAccount(PlayerArguments.ResolvedPlayer target) {
        if (target.uuid() != null) {
            return mojang.lookupUuid(target.uuid());
        }
        String name = target.name();
        UUID tabUuid = PlayerArguments.uuidFromTab(name);
        if (tabUuid != null) {
            Optional<MojangProfile> fromTab = mojang.lookupUuid(tabUuid);
            if (fromTab.isPresent()) {
                return fromTab;
            }
        }
        return mojang.lookupName(name);
    }

    private static void tellUnknown(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target) {
        String name = target.name() != null ? target.name() : target.uuid().toString();
        CommandFeedback.tell(source, QueryMessages.unknownAccount(name));
    }

    private record Resolved(UUID uuid, String username, Optional<PlayerSnapshot> stored) {
    }
}
