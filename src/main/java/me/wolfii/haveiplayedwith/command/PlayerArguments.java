package me.wolfii.haveiplayedwith.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.wolfii.clientdatacommandselector.ClientEntityArgument;
import me.wolfii.clientdatacommandselector.ClientEntitySelector;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerArguments {
    private PlayerArguments() {
    }

    static UUID uuidFromTab(String name) {
        Minecraft client = Minecraft.getInstance();
        AtomicReference<UUID> found = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        client.execute(() -> {
            try {
                ClientPacketListener connection = client.getConnection();
                if (connection != null) {
                    for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                        if (name.equalsIgnoreCase(info.getProfile().name())) {
                            found.set(info.getProfile().id());
                            break;
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found.get();
    }

    static ResolvedPlayer resolvePlayer(CommandContext<FabricClientCommandSource> context, String arg) throws CommandSyntaxException {
        ClientEntitySelector selector = (ClientEntitySelector) context.getArgument(arg, EntitySelector.class);
        try {
            Player found = ClientEntityArgument.getPlayer(context, arg);
            return new ResolvedPlayer(found.getGameProfile().name(), found.getGameProfile().id());
        } catch (CommandSyntaxException e) {
            String name = selector.clientdatacommandupdated$playerName();
            UUID uuid = selector.clientdatacommandupdated$entityUUID();
            if (name != null || uuid != null) {
                return new ResolvedPlayer(name, uuid);
            }
            return null;
        }
    }

    static List<ResolvedPlayer> resolvePlayers(CommandContext<FabricClientCommandSource> context, String arg) throws CommandSyntaxException {
        List<? extends Player> found = ClientEntityArgument.getPlayers(context, arg);
        if (!found.isEmpty()) {
            List<ResolvedPlayer> resolved = new ArrayList<>(found.size());
            for (Player player : found) {
                resolved.add(new ResolvedPlayer(player.getGameProfile().name(), player.getGameProfile().id()));
            }
            return resolved;
        }
        ClientEntitySelector selector = (ClientEntitySelector) context.getArgument(arg, EntitySelector.class);
        String name = selector.clientdatacommandupdated$playerName();
        UUID uuid = selector.clientdatacommandupdated$entityUUID();
        if (name != null || uuid != null) {
            return List.of(new ResolvedPlayer(name, uuid));
        }
        return List.of();
    }

    record ResolvedPlayer(String name, UUID uuid) {
    }
}
