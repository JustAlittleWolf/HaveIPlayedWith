package me.wolfii.command;

import me.wolfii.db.PlayerSnapshot;
import me.wolfii.db.SeenName;
import me.wolfii.db.ServerPlay;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class QueryMessages {
    /** Usernames, past names, and unknown names. */
    public static final int NAME = 0x7CFF9A;
    /** Play time, including compact and hover duration strings. */
    public static final int DURATION = 0x6EC8FF;
    /** Counts (days, sessions, import progress) and calendar timestamps. */
    public static final int COUNT = 0xFFD166;
    /** Player UUIDs in hovers. */
    public static final int UUID_COLOR = 0xC4B5FD;
    public static final int NOTE = 0xFFCC99;
    public static final int SERVER = 0x5EEAD4;

    private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private QueryMessages() {
    }

    public static Component notPlayedWith(String name) {
        return notPlayedWith(name, null);
    }

    public static Component notPlayedWith(String name, UUID uuid) {
        return wording("You have not played with ")
            .append(username(name, uuid))
            .append(wording("."));
    }

    public static Component playedWith(PlayerSnapshot player) {
        MutableComponent duration = data(DurationFormat.compact(player.totalMinutes()), DURATION)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                data(DurationFormat.hover(player.totalMinutes()), DURATION)
            )));
        String dayLabel = player.daysPlayed() == 1 ? "1 day" : player.daysPlayed() + " days";
        MutableComponent days = data(dayLabel, COUNT)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                wording("across ")
                    .append(data(String.valueOf(player.sessionCount()), COUNT))
                    .append(wording(player.sessionCount() == 1 ? " session" : " sessions"))
            )));
        return wording("You have played with ")
            .append(username(player.currentUsername(), player.uuid()))
            .append(wording(" for "))
            .append(duration)
            .append(wording(" across "))
            .append(days)
            .append(wording("."));
    }

    public static Component pastNames(PlayerSnapshot player) {
        List<SeenName> past = player.pastNames();
        MutableComponent line = wording("You have also seen them as ");
        for (int i = 0; i < past.size(); i++) {
            SeenName seen = past.get(i);
            if (i > 0) {
                line.append(wording(i == past.size() - 1 ? " and " : ", "));
            }
            String when = LAST_SEEN.format(seen.lastSeen().atZone(ZoneId.systemDefault()));
            MutableComponent hover = wording("Last seen as ")
                .append(usernameText(seen.username()))
                .append(wording(" at "))
                .append(data(when, COUNT));
            line.append(username(seen.username(), hover));
        }
        line.append(wording(" in the past."));
        return line;
    }

    public static Component seenOn(PlayerSnapshot player) {
        List<ServerPlay> servers = player.servers();
        MutableComponent line = wording("You have seen them on ");
        for (int i = 0; i < servers.size(); i++) {
            ServerPlay server = servers.get(i);
            if (i > 0) {
                line.append(wording(i == servers.size() - 1 ? " and " : ", "));
            }
            line.append(data(server.serverId(), SERVER).withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(data(DurationFormat.hover(server.minutes()), DURATION))
            )));
            line.append(wording(" ("));
            line.append(data(DurationFormat.compact(server.minutes()), DURATION));
            line.append(wording(")"));
        }
        line.append(wording("."));
        return line;
    }

    public static Component note(String note) {
        return wording("Note: ").append(data(note, NOTE));
    }

    public static Component noteSaved(String name) {
        return wording("Saved note for ")
            .append(username(name, (UUID) null))
            .append(wording("."));
    }

    public static Component noteConfirm(String name, UUID uuid) {
        MutableComponent click = clickable(
            "Click here",
            new ClickEvent.RunCommand("/playernote confirm"),
            wording("Save this note for ").append(usernameText(name))
        );
        return wording("You have not played with ")
            .append(username(name, uuid))
            .append(wording(" yet. "))
            .append(click)
            .append(wording(" to save this note anyway."));
    }

    public static Component nothingToConfirm() {
        return wording("There is no pending player note to confirm.");
    }

    public static Component unknownAccount(String name) {
        return wording("Could not find a Minecraft account named ")
            .append(username(name, (UUID) null))
            .append(wording("."));
    }

    public static Component importStatus(String message) {
        return wording(message);
    }

    public static Component importStillRunning(long processed, long total) {
        if (total > 0) {
            return wording("AllTheLogs import is still running (")
                .append(data(String.valueOf(processed), COUNT))
                .append(wording("/"))
                .append(data(String.valueOf(total), COUNT))
                .append(wording(")."));
        }
        return wording("AllTheLogs import is still running (")
            .append(data(String.valueOf(processed), COUNT))
            .append(wording(" messages)."));
    }

    public static Component importStopped(long processed) {
        return wording("AllTheLogs import stopped (")
            .append(data(String.valueOf(processed), COUNT))
            .append(wording(" messages)."));
    }

    public static Component importFinished(long processed) {
        return wording("AllTheLogs import finished (")
            .append(data(String.valueOf(processed), COUNT))
            .append(wording(" messages)."));
    }

    public static Component importProgress(long processed, long total) {
        if (total > 0) {
            int percent = (int) Math.min(100, (processed * 100) / total);
            return wording("AllTheLogs import: ")
                .append(data(String.valueOf(processed), COUNT))
                .append(wording("/"))
                .append(data(String.valueOf(total), COUNT))
                .append(wording(" ("))
                .append(data(percent + "%", COUNT))
                .append(wording(")"));
        }
        return wording("AllTheLogs import: ")
            .append(data(String.valueOf(processed), COUNT))
            .append(wording(" messages..."));
    }

    private static MutableComponent username(String name, UUID uuid) {
        Component hover = uuid == null
            ? wording("Open NameMC")
            : data(uuid.toString(), UUID_COLOR);
        return username(name, hover);
    }

    private static MutableComponent username(String name, Component hover) {
        return usernameText(name).withStyle(style -> style
            .withUnderlined(true)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create("https://namemc.com/profile/" + name)))
            .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static MutableComponent usernameText(String name) {
        return Component.literal(name).withStyle(style -> style
            .withColor(rgb(NAME))
            .withItalic(true));
    }

    private static MutableComponent clickable(String text, ClickEvent click, Component hover) {
        return wording(text).withStyle(style -> style
            .withUnderlined(true)
            .withClickEvent(click)
            .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static MutableComponent wording(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent data(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(rgb(color)));
    }

    private static TextColor rgb(int color) {
        return TextColor.fromRgb(color);
    }
}
