package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class NoteMessages {
    private NoteMessages() {
    }

    public static Component note(PlayerSnapshot player) {
        MutableComponent body = ChatStyle.data(player.note().orElse(""), ChatStyle.NOTE);
        player.noteTakenAt().ifPresent(takenAt -> body.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
            ChatStyle.wording("haveiplayedwith.note.taken", ChatStyle.count(ChatTimes.dateTime(takenAt)))
        ))));
        return ChatStyle.wording("haveiplayedwith.note.label", body);
    }

    public static Component noteSaved(String name) {
        return ChatStyle.wording("haveiplayedwith.note.saved", ChatStyle.username(name));
    }

    public static Component noteCleared(String name) {
        return ChatStyle.wording("haveiplayedwith.note.cleared", ChatStyle.username(name));
    }

    public static Component noteMissing(String name) {
        return ChatStyle.wording("haveiplayedwith.note.missing", ChatStyle.username(name));
    }
}
