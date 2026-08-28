package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.net.MojangClient;
import me.wolfii.haveiplayedwith.store.PlayerDatabase;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerNotes {
    private final PlayerDatabase database;
    private final MojangClient mojang;
    private final ExecutorService worker;
    private final AtomicReference<PendingNote> pendingNote = new AtomicReference<>();

    PlayerNotes(PlayerDatabase database, MojangClient mojang, ExecutorService worker) {
        this.database = database;
        this.mojang = mojang;
        this.worker = worker;
    }

    void setNote(FabricClientCommandSource source, PlayerArguments.ResolvedPlayer target, String note) {
        String cleaned = note.replace('\n', ' ').replace('\r', ' ').strip();
        worker.execute(() -> {
            List<PlayerSnapshot> matches = target.uuid() != null
                ? database.get(target.uuid()).map(List::of).orElseGet(List::of)
                : database.findByName(target.name());
            if (!matches.isEmpty()) {
                for (PlayerSnapshot match : matches) {
                    database.setNote(match.uuid(), match.currentUsername(), cleaned);
                    CommandFeedback.tell(source, QueryMessages.noteSaved(match.currentUsername()));
                }
                return;
            }
            String name = target.name();
            UUID uuid = target.uuid();
            if (uuid == null && name != null) {
                uuid = PlayerArguments.uuidFromTab(name);
                if (uuid == null) {
                    uuid = mojang.lookupName(name).map(MojangClient.Profile::uuid).orElse(null);
                }
            }
            if (uuid == null) {
                CommandFeedback.tell(source, QueryMessages.unknownAccount(name));
                return;
            }
            String username = name != null ? name : uuid.toString();
            pendingNote.set(new PendingNote(uuid, username, cleaned));
            CommandFeedback.tell(source, QueryMessages.noteConfirm(username, uuid));
        });
    }

    void confirmPending(FabricClientCommandSource source) {
        PendingNote pending = pendingNote.getAndSet(null);
        if (pending == null) {
            CommandFeedback.tell(source, QueryMessages.nothingToConfirm());
            return;
        }
        worker.execute(() -> {
            database.setNote(pending.uuid(), pending.username(), pending.note());
            CommandFeedback.tell(source, QueryMessages.noteSaved(pending.username()));
        });
    }

    private record PendingNote(UUID uuid, String username, String note) {
    }
}
