package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.SeenName;
import me.wolfii.haveiplayedwith.store.ServerPlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class QueryMessages {
    private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private QueryMessages() {
    }

    public static Component notPlayedWith(String name) {
        return notPlayedWith(name, null);
    }

    public static Component notPlayedWith(String name, UUID uuid) {
        return ChatStyle.gray("haveiplayedwith.query.not_played", ChatStyle.clickableName(name, ChatStyle.UNKNOWN, true, uuid));
    }

    public static Component noMatchingPlayers() {
        return ChatStyle.gray("haveiplayedwith.query.no_players");
    }

    public static Component playedWith(PlayerSnapshot player) {
        MutableComponent name = ChatStyle.clickableName(player.currentUsername(), ChatStyle.NAME, false, player.uuid());
        MutableComponent duration = ChatStyle.colored(DurationFormat.compact(player.totalMinutes()), ChatStyle.DURATION)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                ChatStyle.colored(DurationFormat.hover(player.totalMinutes()), ChatStyle.DURATION)
            )));
        MutableComponent days = ChatStyle.colored(dayLabel(player.daysPlayed()), ChatStyle.DAYS)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(daysHover(player))));
        MutableComponent sessions = ChatStyle.colored(sessionLabel(player.sessionCount()), ChatStyle.SESSIONS)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(sessionsHover(player))));
        return ChatStyle.gray("haveiplayedwith.query.played", name, duration, days, sessions);
    }

    public static Component pastNames(PlayerSnapshot player) {
        List<SeenName> past = player.pastNames();
        List<Component> names = new ArrayList<>(past.size());
        for (SeenName seen : past) {
            String when = LAST_SEEN.format(seen.lastSeen().atZone(ZoneId.systemDefault()));
            names.add(ChatStyle.colored(seen.username(), ChatStyle.PAST_NAME).withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(Component.translatable("haveiplayedwith.query.past_name.hover", seen.username(), when)
                    .withStyle(style1 -> style1.withColor(ChatStyle.rgb(ChatStyle.PAST_NAME))))
            )));
        }
        return ChatStyle.gray("haveiplayedwith.query.past_names", ChatStyle.join(names));
    }

    public static Component seenOn(PlayerSnapshot player) {
        List<Component> servers = new ArrayList<>(player.servers().size());
        for (ServerPlay server : player.servers()) {
            MutableComponent id = ChatStyle.colored(server.serverId(), ChatStyle.SERVER).withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(ChatStyle.colored(DurationFormat.hover(server.minutes()), ChatStyle.DURATION))
            ));
            servers.add(ChatStyle.gray("haveiplayedwith.query.server.entry", id, ChatStyle.colored(DurationFormat.compact(server.minutes()), ChatStyle.DURATION)));
        }
        return ChatStyle.gray("haveiplayedwith.query.seen_on", ChatStyle.join(servers));
    }

    public static Component unknownAccount(String name) {
        return ChatStyle.gray("haveiplayedwith.query.unknown_account", ChatStyle.clickableName(name, ChatStyle.UNKNOWN, true, null));
    }

    private static Component dayLabel(int days) {
        return days == 1
            ? Component.translatable("haveiplayedwith.query.days.one")
            : Component.translatable("haveiplayedwith.query.days", days);
    }

    private static Component sessionLabel(int sessions) {
        return sessions == 1
            ? Component.translatable("haveiplayedwith.query.sessions.one")
            : Component.translatable("haveiplayedwith.query.sessions", sessions);
    }

    private static Component daysHover(PlayerSnapshot player) {
        return player.lastPlayedBeforeToday()
            .map(day -> ChatStyle.gray("haveiplayedwith.query.last_played", DATE.format(day)))
            .orElseGet(() -> ChatStyle.gray("haveiplayedwith.query.last_played.today"));
    }

    private static Component sessionsHover(PlayerSnapshot player) {
        return player.mostPlayedServer()
            .map(server -> ChatStyle.gray("haveiplayedwith.query.most_server", ChatStyle.colored(server.serverId(), ChatStyle.SERVER)))
            .orElseGet(() -> ChatStyle.gray("haveiplayedwith.query.most_server.none"));
    }
}
