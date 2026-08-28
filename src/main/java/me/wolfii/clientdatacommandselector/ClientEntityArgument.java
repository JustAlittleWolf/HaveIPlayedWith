package me.wolfii.clientdatacommandselector;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

public interface ClientEntityArgument {
    static EntityArgument entity() {
        return ((ClientEntityArgument) EntityArgument.entity()).clientDataCommandUpdated$withAlwaysAllowAtSelectors();
    }

    static EntityArgument entities() {
        return ((ClientEntityArgument) EntityArgument.entities()).clientDataCommandUpdated$withAlwaysAllowAtSelectors();
    }

    static EntityArgument player() {
        return ((ClientEntityArgument) EntityArgument.player()).clientDataCommandUpdated$withAlwaysAllowAtSelectors();
    }

    static EntityArgument players() {
        return ((ClientEntityArgument) EntityArgument.players()).clientDataCommandUpdated$withAlwaysAllowAtSelectors();
    }

    static Entity getEntity(CommandContext<FabricClientCommandSource> context, String name) throws CommandSyntaxException {
        return context.getArgument(name, EntitySelector.class).findSingleEntity(new FabricClientCommandSourceStack(context.getSource()));
    }

    static List<? extends Entity> getEntities(CommandContext<FabricClientCommandSource> context, String name) throws CommandSyntaxException {
        return context.getArgument(name, EntitySelector.class).findEntities(new FabricClientCommandSourceStack(context.getSource()));
    }

    static Player getPlayer(CommandContext<FabricClientCommandSource> context, String name) throws CommandSyntaxException {
        return ((ClientEntitySelector) context.getArgument(name, EntitySelector.class)).clientdatacommandupdated$findSinglePlayerClient(new FabricClientCommandSourceStack(context.getSource()));
    }

    static List<? extends Player> getPlayers(CommandContext<FabricClientCommandSource> context, String name) throws CommandSyntaxException {
        return ((ClientEntitySelector) context.getArgument(name, EntitySelector.class)).clientdatacommandupdated$findPlayersClient(new FabricClientCommandSourceStack(context.getSource()));
    }

    EntityArgument clientDataCommandUpdated$withAlwaysAllowAtSelectors();
}
