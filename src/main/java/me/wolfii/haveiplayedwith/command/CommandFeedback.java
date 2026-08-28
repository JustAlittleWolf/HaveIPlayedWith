package me.wolfii.haveiplayedwith.command;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class CommandFeedback {
    private CommandFeedback() {
    }

    static void tell(FabricClientCommandSource source, Component message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(message));
    }
}
