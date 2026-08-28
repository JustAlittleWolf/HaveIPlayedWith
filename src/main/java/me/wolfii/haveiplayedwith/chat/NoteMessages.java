package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
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
        MutableComponent body = ChatStyle.data(player.note().orElse(""), ChatStyle.NOTE);
        player.noteTakenAt().ifPresent(takenAt -> {
            String when = LAST_SEEN.format(takenAt.atZone(ZoneId.systemDefault()));
            body.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                ChatStyle.wording("haveiplayedwith.note.taken", ChatStyle.count(when))
            )));
        });
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
            ChatStyle.username(name, uuid),
            click
        );
    }

    public static Component nothingToConfirm() {
        return ChatStyle.wording("haveiplayedwith.note.nothing");
    }
}
