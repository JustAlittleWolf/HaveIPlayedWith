package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenameMessagesTest {
    @Test
    void renamedMessageNamesTheOldAndNewNames() {
        Component message = RenameMessages.playerRenamed(
            "Steve",
            "Alex",
            UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6")
        );
        TranslatableContents contents = (TranslatableContents) message.getContents();
        assertEquals("haveiplayedwith.observe.renamed", contents.getKey());
        assertEquals(2, contents.getArgs().length);
    }
}
