package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public final class NoteMessages {
    private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private NoteMessages() {
    }

    public static Component note(PlayerSnapshot player) {
        MutableComponent body = ChatStyle.colored(player.note().orElse(""), ChatStyle.NOTE);
        player.noteTakenAt().ifPresent(takenAt -> {
            String when = LAST_SEEN.format(takenAt.atZone(ZoneId.systemDefault()));
            body.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                ChatStyle.gray("haveiplayedwith.note.taken", when)
            )));
        });
        return ChatStyle.gray("haveiplayedwith.note.label", body);
    }

    public static Component noteSaved(String name) {
        return ChatStyle.gray("haveiplayedwith.note.saved", ChatStyle.clickableName(name, ChatStyle.NAME, false, null));
    }

    public static Component noteConfirm(String name, UUID uuid) {
        MutableComponent click = Component.translatable("haveiplayedwith.note.confirm.click").withStyle(style -> style
            .withColor(ChatStyle.rgb(ChatStyle.CONFIRM))
            .withUnderlined(true)
            .withClickEvent(new ClickEvent.RunCommand("/playernote confirm"))
            .withHoverEvent(new HoverEvent.ShowText(
                Component.translatable("haveiplayedwith.note.confirm.hover", name).withStyle(ChatFormatting.GRAY)
            ))
        );
        return ChatStyle.gray("haveiplayedwith.note.confirm", ChatStyle.clickableName(name, ChatStyle.UNKNOWN, true, uuid), click);
    }

    public static Component nothingToConfirm() {
        return ChatStyle.gray("haveiplayedwith.note.nothing");
    }
}
