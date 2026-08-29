package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.SeenName;
import me.wolfii.haveiplayedwith.store.ServerPlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QueryMessages {
    private QueryMessages() {
    }

    public static Component notPlayedWith(String name) {
        return notPlayedWith(name, null);
    }

    public static Component notPlayedWith(String name, UUID uuid) {
        return ChatStyle.wording("haveiplayedwith.query.not_played", ChatStyle.username(name, uuid));
    }

    public static Component playedWith(PlayerSnapshot player) {
        MutableComponent duration = DurationFormat.compact(player.totalMinutes()).copy()
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(durationHover(player))));
        MutableComponent days = dayLabel(player.daysPlayed()).copy()
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(daysHover(player))));
        return ChatStyle.wording(
            "haveiplayedwith.query.played",
            ChatStyle.username(player.currentUsername(), player.uuid()),
            duration,
            days
        );
    }

    public static Component pastNames(PlayerSnapshot player) {
        List<SeenName> past = player.pastNames();
        List<Component> names = new ArrayList<>(past.size());
        for (SeenName seen : past) {
            Component hover = ChatStyle.wording(
                "haveiplayedwith.query.past_name.hover",
                ChatStyle.usernameText(seen.username()),
                ChatStyle.count(ChatTimes.dateTime(seen.lastSeen()))
            );
            names.add(ChatStyle.usernameWithHover(seen.username(), hover));
        }
        return ChatStyle.wording("haveiplayedwith.query.past_names", ChatStyle.join(names));
    }

    public static Component seenOn(PlayerSnapshot player) {
        List<Component> servers = new ArrayList<>(player.servers().size());
        for (ServerPlay server : player.servers()) {
            MutableComponent id = ChatStyle.data(server.serverId(), ChatStyle.SERVER).withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(DurationFormat.hover(server.minutes()))
            ));
            servers.add(ChatStyle.wording("haveiplayedwith.query.server.entry", id, DurationFormat.compact(server.minutes())));
        }
        return ChatStyle.wording("haveiplayedwith.query.seen_on", ChatStyle.join(servers));
    }

    public static Component unknownAccount(String name) {
        return ChatStyle.wording("haveiplayedwith.query.unknown_account", ChatStyle.username(name));
    }

    private static Component dayLabel(int days) {
        return ChatStyle.wording(
            days == 1 ? "haveiplayedwith.query.days.one" : "haveiplayedwith.query.days",
            ChatStyle.count(days)
        );
    }

    private static Component sessionLabel(int sessions) {
        return ChatStyle.wording(
            sessions == 1 ? "haveiplayedwith.query.sessions.one" : "haveiplayedwith.query.sessions",
            ChatStyle.count(sessions)
        );
    }

    private static Component durationHover(PlayerSnapshot player) {
        return hoverLines(DurationFormat.hover(player.totalMinutes()), lastPlayedHover(player));
    }

    private static Component lastPlayedHover(PlayerSnapshot player) {
        return player.lastPlayedBeforeToday()
            .map(day -> ChatStyle.wording("haveiplayedwith.query.last_played", ChatStyle.count(ChatTimes.date(day))))
            .orElseGet(() -> ChatStyle.wording("haveiplayedwith.query.last_played.today"));
    }

    private static Component daysHover(PlayerSnapshot player) {
        return hoverLines(sessionsHover(player), mostServerHover(player));
    }

    private static Component sessionsHover(PlayerSnapshot player) {
        return ChatStyle.wording("haveiplayedwith.query.sessions.across", sessionLabel(player.sessionCount()));
    }

    private static Component mostServerHover(PlayerSnapshot player) {
        return player.mostPlayedServer()
            .map(server -> ChatStyle.wording("haveiplayedwith.query.most_server", ChatStyle.data(server.serverId(), ChatStyle.SERVER)))
            .orElseGet(() -> ChatStyle.wording("haveiplayedwith.query.most_server.none"));
    }

    private static Component hoverLines(Component first, Component second) {
        return Component.empty().append(first).append(Component.literal("\n")).append(second);
    }
}
