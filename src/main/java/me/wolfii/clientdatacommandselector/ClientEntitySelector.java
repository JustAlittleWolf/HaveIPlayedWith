package me.wolfii.clientdatacommandselector;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface ClientEntitySelector {
    Entity findSingleEntity(CommandSourceStack sender) throws CommandSyntaxException;

    List<? extends Entity> findEntities(CommandSourceStack sender) throws CommandSyntaxException;

    Player clientdatacommandupdated$findSinglePlayerClient(FabricClientCommandSourceStack sender) throws CommandSyntaxException;

    List<? extends Player> clientdatacommandupdated$findPlayersClient(FabricClientCommandSourceStack sender) throws CommandSyntaxException;

    @Nullable String clientdatacommandupdated$playerName();

    @Nullable UUID clientdatacommandupdated$entityUUID();
}