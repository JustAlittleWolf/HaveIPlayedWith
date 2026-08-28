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
    static final int NAME = 0x7CFF9A;
    static final int DURATION = 0x6EC8FF;
    static final int DAYS = 0xFFD166;
    static final int UUID_COLOR = 0xC4B5FD;
    static final int PAST_NAME = 0xA5B4FC;
    static final int NOTE = 0xFF9F43;
    static final int UNKNOWN = 0xFF8FAB;
    static final int CONFIRM = 0xFFE066;
    static final int SESSIONS = 0xF0ABFC;
    static final int SERVER = 0x5EEAD4;

    private ChatStyle() {
    }

    static Component join(List<Component> items) {
        MutableComponent result = Component.empty();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                result.append(gray(i == items.size() - 1 ? "haveiplayedwith.list.and" : "haveiplayedwith.list.comma"));
            }
            result.append(items.get(i));
        }
        return result;
    }

    static MutableComponent clickableName(String name, int color, boolean italic, UUID uuid) {
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

    static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(rgb(color)));
    }

    static MutableComponent colored(Component text, int color) {
        return text.copy().withStyle(style -> style.withColor(rgb(color)));
    }

    static MutableComponent gray(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }

    static TextColor rgb(int color) {
        return TextColor.fromRgb(color);
    }
}
