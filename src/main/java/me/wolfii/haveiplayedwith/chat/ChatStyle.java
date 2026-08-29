package me.wolfii.haveiplayedwith.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.net.URI;
import java.util.List;
import java.util.UUID;

final class ChatStyle {
    /** Current, past, and unknown Minecraft usernames. */
    static final int NAME = 0x7CFF9A;
    /** Play time, including compact and hover duration values. */
    static final int DURATION = 0x6EC8FF;
    /** Counts (days, sessions, import progress) and calendar timestamps. */
    static final int COUNT = 0xFFD166;
    /** Player UUIDs in hovers. */
    static final int UUID_COLOR = 0xC4B5FD;
    static final int NOTE = 0xFFCC99;
    static final int SERVER = 0xFF9A9A;

    private ChatStyle() {
    }

    static Component join(List<Component> items) {
        MutableComponent result = Component.empty();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                result.append(wording(i == items.size() - 1 ? "haveiplayedwith.list.and" : "haveiplayedwith.list.comma"));
            }
            result.append(items.get(i));
        }
        return result;
    }

    static MutableComponent username(String name) {
        return username(name, null, false);
    }

    static MutableComponent username(String name, UUID uuid) {
        return username(name, uuid, false);
    }

    /** Hovers show the UUID, or an invitation to open NameMC when the UUID is unknown. */
    static MutableComponent username(String name, UUID uuid, boolean italic) {
        Component hover = uuid == null
            ? wording("haveiplayedwith.namemc.open")
            : data(uuid.toString(), UUID_COLOR);
        return linkedUsername(name, hover, italic);
    }

    static MutableComponent usernameWithHover(String name, Component hover) {
        return linkedUsername(name, hover, false);
    }

    private static MutableComponent linkedUsername(String name, Component hover, boolean italic) {
        MutableComponent result = usernameText(name).withStyle(style -> style
            .withUnderlined(true)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create("https://namemc.com/profile/" + name)))
            .withHoverEvent(new HoverEvent.ShowText(hover)));
        if (italic) {
            result = result.withStyle(style -> style.withItalic(true));
        }
        return result;
    }

    static MutableComponent usernameText(String name) {
        return Component.literal(name).withStyle(style -> style.withColor(rgb(NAME)));
    }

    static MutableComponent clickable(String key, ClickEvent click, Component hover) {
        return wording(key).withStyle(style -> style
            .withUnderlined(true)
            .withClickEvent(click)
            .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    static MutableComponent duration(Object value) {
        return data(value, DURATION);
    }

    static MutableComponent count(Object value) {
        return data(value, COUNT);
    }

    static MutableComponent data(Object value, int color) {
        return Component.literal(String.valueOf(value)).withStyle(style -> style.withColor(rgb(color)));
    }

    static MutableComponent wording(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }

    static TextColor rgb(int color) {
        return TextColor.fromRgb(color);
    }
}
