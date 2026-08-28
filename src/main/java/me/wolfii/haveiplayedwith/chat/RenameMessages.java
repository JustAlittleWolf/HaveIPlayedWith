package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class RenameMessages {
    private RenameMessages() {
    }

    public static Component playerRenamed(String previous, String current, UUID uuid) {
        return ChatStyle.wording(
            "haveiplayedwith.observe.renamed",
            ChatStyle.username(previous, uuid),
            ChatStyle.username(current, uuid)
        );
    }
}
