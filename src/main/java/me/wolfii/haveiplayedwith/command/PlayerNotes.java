package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.chat.NoteMessages;
import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.profile.Profile;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerNotes {
    private static final String CLEAR = "clear";

    private final PlayerStore players;
    private final ProfileApi profiles;
    private final ExecutorService worker;

    PlayerNotes(PlayerStore players, ProfileApi profiles, ExecutorService worker) {
        this.players = players;
        this.profiles = profiles;
        this.worker = worker;
    }

    void show(FabricClientCommandSource source, PlayerTargetArgument.PlayerTarget target) {
        worker.execute(() -> {
            List<Resolved> matches = resolve(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (Resolved match : matches) {
                PlayerSnapshot stored = match.stored().orElse(null);
                if (stored != null && stored.note().isPresent()) {
                    tell(source, NoteMessages.note(stored));
                } else {
                    tell(source, NoteMessages.noteMissing(match.username()));
                }
            }
        });
    }

    void write(FabricClientCommandSource source, PlayerTargetArgument.PlayerTarget target, String note) {
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
                tell(source, NoteMessages.noteSaved(match.username()));
            }
        });
    }

    private void clear(FabricClientCommandSource source, PlayerTargetArgument.PlayerTarget target) {
        worker.execute(() -> {
            List<Resolved> matches = resolve(target);
            if (matches.isEmpty()) {
                tellUnknown(source, target);
                return;
            }
            for (Resolved match : matches) {
                if (match.stored().isEmpty()) {
                    tell(source, NoteMessages.noteMissing(match.username()));
                    continue;
                }
                players.setNote(match.uuid(), match.username(), "");
                tell(source, NoteMessages.noteCleared(match.username()));
            }
        });
    }

    /**
     * Players already in the store, or a real Minecraft account resolved from tab / the profile API.
     * Made-up names that are not a Minecraft account are rejected.
     */
    private List<Resolved> resolve(PlayerTargetArgument.PlayerTarget target) {
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

    private List<PlayerSnapshot> storedMatches(PlayerTargetArgument.PlayerTarget target) {
        if (target.uuid() != null) {
            return players.get(target.uuid()).map(List::of).orElseGet(List::of);
        }
        return players.findByName(target.name());
    }

    private Optional<Profile> minecraftAccount(PlayerTargetArgument.PlayerTarget target) {
        if (target.uuid() != null) {
            return profiles.lookupUuid(target.uuid());
        }
        String name = target.name();
        UUID tabUuid = uuidFromTab(name);
        if (tabUuid != null) {
            Optional<Profile> fromTab = profiles.lookupUuid(tabUuid);
            if (fromTab.isPresent()) {
                return fromTab;
            }
        }
        return profiles.lookupName(name);
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

    private static void tellUnknown(FabricClientCommandSource source, PlayerTargetArgument.PlayerTarget target) {
        String name = target.name() != null ? target.name() : target.uuid().toString();
        tell(source, QueryMessages.unknownAccount(name));
    }

    private static void tell(FabricClientCommandSource source, Component message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(message));
    }

    private record Resolved(UUID uuid, String username, Optional<PlayerSnapshot> stored) {
    }
}
