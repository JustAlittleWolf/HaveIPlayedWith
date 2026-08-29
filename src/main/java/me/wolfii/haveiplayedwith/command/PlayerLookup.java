package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.chat.NoteMessages;
import me.wolfii.haveiplayedwith.chat.QueryMessages;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

final class PlayerLookup {
    private final PlayerStore players;
    private final ProfileApi profiles;
    private final ExecutorService worker;
    private final Supplier<String> liveSessionId;

    PlayerLookup(PlayerStore players, ProfileApi profiles, ExecutorService worker, Supplier<String> liveSessionId) {
        this.players = players;
        this.profiles = profiles;
        this.worker = worker;
        this.liveSessionId = liveSessionId;
    }

    void query(FabricClientCommandSource source, PlayerTargetArgument.PlayerTarget target) {
        worker.execute(() -> {
            if (target.uuid() != null) {
                PlayerSnapshot match = players.get(target.uuid()).orElse(null);
                if (match == null) {
                    String name = target.name() != null ? target.name() : target.uuid().toString();
                    tell(source, QueryMessages.notPlayedWith(name, target.uuid()));
                    return;
                }
                show(source, match);
                return;
            }
            List<PlayerSnapshot> matches = players.findByName(target.name());
            if (matches.isEmpty()) {
                tell(source, QueryMessages.notPlayedWith(target.name()));
                return;
            }
            for (PlayerSnapshot match : matches) {
                show(source, match);
            }
        });
    }

    /** Asks whether the stored name is still current, so a lookup never shows a stale one. */
    private PlayerSnapshot refreshed(PlayerSnapshot match) {
        profiles.lookupUuid(match.uuid()).ifPresent(profile -> {
            if (!profile.username().equals(match.currentUsername())) {
                players.applyUsername(match.uuid(), profile.username(), Instant.now());
            }
        });
        return players.get(match.uuid()).orElse(match);
    }

    private void show(FabricClientCommandSource source, PlayerSnapshot match) {
        PlayerSnapshot latest = refreshed(match);
        long currentSessionMinutes = players.sessionMinutes(latest.uuid(), liveSessionId.get());
        if (latest.hasPlayedBefore(currentSessionMinutes)) {
            tell(source, QueryMessages.playedWith(latest));
            if (!latest.pastNames().isEmpty()) {
                tell(source, QueryMessages.pastNames(latest));
            }
        } else {
            tell(source, QueryMessages.notPlayedWith(latest.currentUsername(), latest.uuid()));
        }
        latest.note().ifPresent(note -> tell(source, NoteMessages.note(latest)));
    }

    private static void tell(FabricClientCommandSource source, Component message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(message));
    }
}
