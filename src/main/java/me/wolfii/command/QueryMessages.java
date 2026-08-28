package me.wolfii.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import me.wolfii.db.PlayerSnapshot;
import me.wolfii.db.SeenName;

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

	private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

	private QueryMessages() {
	}

	public static Component notPlayedWith(String name) {
		return notPlayedWith(name, null);
	}

	public static Component notPlayedWith(String name, UUID uuid) {
		return Component.literal("You have not played with ").withStyle(ChatFormatting.GRAY)
			.append(clickableName(name, UNKNOWN, true, uuid))
			.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
	}

	public static Component playedWith(PlayerSnapshot player) {
		MutableComponent name = clickableName(player.currentUsername(), NAME, false, player.uuid());
		MutableComponent duration = colored(DurationFormat.compact(player.totalMinutes()), DURATION)
			.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
				Component.literal(DurationFormat.hover(player.totalMinutes())).withStyle(style1 -> style1.withColor(rgb(DURATION)))
			)));
		String dayLabel = player.daysPlayed() == 1 ? "1 day" : player.daysPlayed() + " days";
		MutableComponent days = colored(dayLabel, DAYS)
			.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
				Component.literal("across ").withStyle(ChatFormatting.GRAY)
					.append(colored(String.valueOf(player.sessionCount()), SESSIONS))
					.append(Component.literal(player.sessionCount() == 1 ? " session" : " sessions").withStyle(ChatFormatting.GRAY))
			)));
		return Component.literal("You have played with ").withStyle(ChatFormatting.GRAY)
			.append(name)
			.append(Component.literal(" for ").withStyle(ChatFormatting.GRAY))
			.append(duration)
			.append(Component.literal(" across ").withStyle(ChatFormatting.GRAY))
			.append(days)
			.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
	}

	public static Component pastNames(PlayerSnapshot player) {
		List<SeenName> past = player.pastNames();
		MutableComponent line = Component.literal("You have also seen them as ").withStyle(ChatFormatting.GRAY);
		for (int i = 0; i < past.size(); i++) {
			SeenName seen = past.get(i);
			if (i > 0) {
				if (i == past.size() - 1) {
					line.append(Component.literal(" and ").withStyle(ChatFormatting.GRAY));
				} else {
					line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
				}
			}
			String when = LAST_SEEN.format(seen.lastSeen().atZone(ZoneId.systemDefault()));
			line.append(colored(seen.username(), PAST_NAME).withStyle(style -> style.withHoverEvent(
				new HoverEvent.ShowText(Component.literal("Last seen as " + seen.username() + " at " + when)
					.withStyle(style1 -> style1.withColor(rgb(PAST_NAME))))
			)));
		}
		line.append(Component.literal(" in the past.").withStyle(ChatFormatting.GRAY));
		return line;
	}

	public static Component note(String note) {
		return Component.literal("Note: ").withStyle(ChatFormatting.GRAY)
			.append(colored(note, NOTE));
	}

	public static Component noteSaved(String name) {
		return Component.literal("Saved note for ").withStyle(ChatFormatting.GRAY)
			.append(clickableName(name, NAME, false, null))
			.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
	}

	public static Component noteConfirm(String name, UUID uuid, String note) {
		String command = "/playernote confirm " + uuid + " " + note;
		MutableComponent click = Component.literal("Click here").withStyle(style -> style
			.withColor(rgb(CONFIRM))
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Save this note for " + name).withStyle(ChatFormatting.GRAY)))
		);
		return Component.literal("You have not played with ").withStyle(ChatFormatting.GRAY)
			.append(clickableName(name, UNKNOWN, true, uuid))
			.append(Component.literal(" yet. ").withStyle(ChatFormatting.GRAY))
			.append(click)
			.append(Component.literal(" to save this note anyway.").withStyle(ChatFormatting.GRAY));
	}

	public static Component unknownAccount(String name) {
		return Component.literal("Could not find a Minecraft account named ").withStyle(ChatFormatting.GRAY)
			.append(clickableName(name, UNKNOWN, true, null))
			.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
	}

	public static Component importStatus(String message) {
		return Component.literal(message).withStyle(ChatFormatting.GRAY);
	}

	private static MutableComponent clickableName(String name, int color, boolean italic, UUID uuid) {
		MutableComponent hover = uuid == null
			? Component.literal("Open NameMC").withStyle(ChatFormatting.GRAY)
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

	private static TextColor rgb(int color) {
		return TextColor.fromRgb(color);
	}
}
