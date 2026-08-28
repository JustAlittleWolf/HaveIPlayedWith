package me.wolfii.command;

import me.wolfii.db.PlayerSnapshot;
import me.wolfii.db.SeenName;
import me.wolfii.db.ServerPlay;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class QueryMessages {
    public static final int NAME = 0x7CFF9A;
    public static final int DURATION = 0x6EC8FF;
    public static final int DAYS = 0xFFD166;
    public static final int UUID_COLOR = 0xC4B5FD;
    public static final int PAST_NAME = 0xA5B4FC;
    public static final int NOTE = 0xFF9F43;
    public static final int UNKNOWN = 0xFF8FAB;
    public static final int CONFIRM = 0xFFE066;
    public static final int SESSIONS = 0xF0ABFC;
    public static final int SERVER = 0x5EEAD4;

    private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private QueryMessages() {
    }

    public static Component notPlayedWith(String name) {
        return notPlayedWith(name, null);
    }

    public static Component notPlayedWith(String name, UUID uuid) {
        return gray("haveiplayedwith.query.not_played", clickableName(name, UNKNOWN, true, uuid));
    }

    public static Component noMatchingPlayers() {
        return gray("haveiplayedwith.query.no_players");
    }

    public static Component playedWith(PlayerSnapshot player) {
        MutableComponent name = clickableName(player.currentUsername(), NAME, false, player.uuid());
        MutableComponent duration = colored(DurationFormat.compact(player.totalMinutes()), DURATION)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                colored(DurationFormat.hover(player.totalMinutes()), DURATION)
            )));
        MutableComponent days = colored(dayLabel(player.daysPlayed()), DAYS)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(sessionsHover(player.sessionCount()))));
        return gray("haveiplayedwith.query.played", name, duration, days);
    }

    public static Component pastNames(PlayerSnapshot player) {
        List<SeenName> past = player.pastNames();
        List<Component> names = new ArrayList<>(past.size());
        for (SeenName seen : past) {
            String when = LAST_SEEN.format(seen.lastSeen().atZone(ZoneId.systemDefault()));
            names.add(colored(seen.username(), PAST_NAME).withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(Component.translatable("haveiplayedwith.query.past_name.hover", seen.username(), when)
                    .withStyle(style1 -> style1.withColor(rgb(PAST_NAME))))
            )));
        }
        return gray("haveiplayedwith.query.past_names", join(names));
    }

    public static Component seenOn(PlayerSnapshot player) {
        List<Component> servers = new ArrayList<>(player.servers().size());
        for (ServerPlay server : player.servers()) {
            MutableComponent id = colored(server.serverId(), SERVER).withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(colored(DurationFormat.hover(server.minutes()), DURATION))
            ));
            servers.add(gray("haveiplayedwith.query.server.entry", id, colored(DurationFormat.compact(server.minutes()), DURATION)));
        }
        return gray("haveiplayedwith.query.seen_on", join(servers));
    }

    public static Component note(String note) {
        return gray("haveiplayedwith.note.label", colored(note, NOTE));
    }

    public static Component noteSaved(String name) {
        return gray("haveiplayedwith.note.saved", clickableName(name, NAME, false, null));
    }

    public static Component noteConfirm(String name, UUID uuid) {
        MutableComponent click = Component.translatable("haveiplayedwith.note.confirm.click").withStyle(style -> style
            .withColor(rgb(CONFIRM))
            .withUnderlined(true)
            .withClickEvent(new ClickEvent.RunCommand("/playernote confirm"))
            .withHoverEvent(new HoverEvent.ShowText(
                Component.translatable("haveiplayedwith.note.confirm.hover", name).withStyle(ChatFormatting.GRAY)
            ))
        );
        return gray("haveiplayedwith.note.confirm", clickableName(name, UNKNOWN, true, uuid), click);
    }

    public static Component nothingToConfirm() {
        return gray("haveiplayedwith.note.nothing");
    }

    public static Component unknownAccount(String name) {
        return gray("haveiplayedwith.query.unknown_account", clickableName(name, UNKNOWN, true, null));
    }

    public static Component importNotRunning() {
        return gray("haveiplayedwith.import.not_running");
    }

    public static Component importStopping() {
        return gray("haveiplayedwith.import.stopping");
    }

    public static Component importSilenced() {
        return gray("haveiplayedwith.import.silenced");
    }

    public static Component importUnsilenced() {
        return gray("haveiplayedwith.import.unsilenced");
    }

    public static Component importStillRunning(long processed, long total) {
        if (total > 0) {
            return gray("haveiplayedwith.import.still_running.counts", processed, total);
        }
        return gray("haveiplayedwith.import.still_running.messages", processed);
    }

    public static Component importStopped(long processed) {
        return gray("haveiplayedwith.import.stopped", processed);
    }

    public static Component importResuming() {
        return gray("haveiplayedwith.import.resuming");
    }

    public static Component importAlreadyRunning() {
        return gray("haveiplayedwith.import.already_running");
    }

    public static Component importStarting() {
        return gray("haveiplayedwith.import.starting");
    }

    public static Component importNotReady() {
        return gray("haveiplayedwith.import.not_ready");
    }

    public static Component importFinished(long processed) {
        return gray("haveiplayedwith.import.finished", processed);
    }

    public static Component importFailed() {
        return gray("haveiplayedwith.import.failed");
    }

    public static Component importProgress(long processed, long total) {
        int percent = (int) Math.min(100, (processed * 100) / total);
        return gray("haveiplayedwith.import.progress.counts", processed, total, percent);
    }

    public static Component importProgressMessages(long processed) {
        return gray("haveiplayedwith.import.progress.messages", processed);
    }

    private static Component dayLabel(int days) {
        return days == 1
            ? Component.translatable("haveiplayedwith.query.days.one")
            : Component.translatable("haveiplayedwith.query.days", days);
    }

    private static Component sessionsHover(int sessions) {
        Component count = colored(String.valueOf(sessions), SESSIONS);
        return sessions == 1
            ? Component.translatable("haveiplayedwith.query.sessions.hover.one", count).withStyle(ChatFormatting.GRAY)
            : Component.translatable("haveiplayedwith.query.sessions.hover", count).withStyle(ChatFormatting.GRAY);
    }

    private static Component join(List<Component> items) {
        MutableComponent result = Component.empty();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                result.append(gray(i == items.size() - 1 ? "haveiplayedwith.list.and" : "haveiplayedwith.list.comma"));
            }
            result.append(items.get(i));
        }
        return result;
    }

    private static MutableComponent clickableName(String name, int color, boolean italic, UUID uuid) {
        MutableComponent hover = uuid == null
            ? gray("haveiplayedwith.namemc.open")
            : Component.literal(uuid.toString()).withStyle(style -> style.withColor(rgb(UUID_COLOR)));
        return Component.literal(name).withStyle(style -> style
            .withColor(rgb(color))
            .withUnderlined(true)
            .withItalic(italic)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create("https://namemc.com/profile/" + name)))
            .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(rgb(color)));
    }

    private static MutableComponent colored(Component text, int color) {
        return text.copy().withStyle(style -> style.withColor(rgb(color)));
    }

    private static MutableComponent gray(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }

    private static TextColor rgb(int color) {
        return TextColor.fromRgb(color);
    }
}
