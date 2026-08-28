package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.chat.NoteMessages;
import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.mojang.MojangProfile;
import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerNotes {
    private final PlayerStore players;
    private final MojangProfileApi mojang;
    private final ExecutorService worker;
    private final AtomicReference<PendingNote> pendingNote = new AtomicReference<>();

    PlayerNotes(PlayerStore players, MojangProfileApi mojang, ExecutorService worker) {
        this.players = players;
        this.mojang = mojang;
        this.worker = worker;
    }

    void setNote(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target, String note) {
        String cleaned = note.replace('\n', ' ').replace('\r', ' ').strip();
        worker.execute(() -> {
            List<PlayerSnapshot> matches = target.uuid() != null
                ? players.get(target.uuid()).map(List::of).orElseGet(List::of)
                : players.findByName(target.name());
            if (!matches.isEmpty()) {
                for (PlayerSnapshot match : matches) {
                    players.setNote(match.uuid(), match.currentUsername(), cleaned);
                    CommandFeedback.tell(source, NoteMessages.noteSaved(match.currentUsername()));
                }
                return;
            }
            String name = target.name();
            UUID uuid = target.uuid();
            if (uuid == null && name != null) {
                uuid = PlayerArguments.uuidFromTab(name);
                if (uuid == null) {
                    uuid = mojang.lookupName(name).map(MojangProfile::uuid).orElse(null);
                }
            }
            if (uuid == null) {
                CommandFeedback.tell(source, QueryMessages.unknownAccount(name));
                return;
            }
            String username = name != null ? name : uuid.toString();
            pendingNote.set(new PendingNote(uuid, username, cleaned));
            CommandFeedback.tell(source, NoteMessages.noteConfirm(username, uuid));
        });
    }

    void confirmPending(FabricClientCommandSource source) {
        PendingNote pending = pendingNote.getAndSet(null);
        if (pending == null) {
            CommandFeedback.tell(source, NoteMessages.nothingToConfirm());
            return;
        }
        worker.execute(() -> {
            players.setNote(pending.uuid(), pending.username(), pending.note());
            CommandFeedback.tell(source, NoteMessages.noteSaved(pending.username()));
        });
    }

    private record PendingNote(UUID uuid, String username, String note) {
    }
}
