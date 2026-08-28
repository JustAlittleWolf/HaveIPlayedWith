package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class RenameMessages {
    private RenameMessages() {
    }

    public static Component playerRenamed(String previous, String current, UUID uuid) {
        return ChatStyle.gray(
            "haveiplayedwith.observe.renamed",
            ChatStyle.clickableName(previous, ChatStyle.PAST_NAME, false, uuid),
            ChatStyle.clickableName(current, ChatStyle.NAME, false, uuid)
        );
    }
}
