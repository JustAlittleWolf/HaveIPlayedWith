package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.UUID;

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

    public static Component noteConfirm(String name, UUID uuid) {
        Component click = ChatStyle.clickable(
            "haveiplayedwith.note.confirm.click",
            new ClickEvent.RunCommand("/playernote confirm"),
            ChatStyle.wording("haveiplayedwith.note.confirm.hover", ChatStyle.usernameText(name))
        );
        return ChatStyle.wording(
            "haveiplayedwith.note.confirm",
            ChatStyle.username(name, uuid, true),
            click
        );
    }

    public static Component nothingToConfirm() {
        return ChatStyle.wording("haveiplayedwith.note.nothing");
    }
}
