package me.wolfii.clientdatacommandselector.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.wolfii.clientdatacommandselector.ClientEntitySelector;
import me.wolfii.clientdatacommandselector.FabricClientCommandSourceStack;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin implements ClientEntitySelector {
    @Shadow
    @Final
    private @Nullable String playerName;

    @Shadow
    @Final
    private @Nullable UUID entityUUID;

    @Shadow
    @Final
    private Function<Vec3, Vec3> position;
    @Shadow
    @Final
    private boolean currentEntity;
    @Shadow
    @Final
    private EntityTypeTest<Entity, ?> type;

    @Shadow
    @Nullable
    protected abstract AABB getAbsoluteAabb(Vec3 pos);

    @Shadow
    protected abstract Predicate<Entity> getPredicate(Vec3 pos, @Nullable AABB absoluteAabb, @Nullable FeatureFlagSet enabledFeatures);

    @Shadow
    protected abstract int getResultLimit();

    @Shadow
    protected abstract <T extends Entity> List<T> sortAndLimit(Vec3 pos, List<T> result);

    @WrapMethod(method = "checkPermissions")
    private void overrideCheckPermissions(CommandSourceStack sender, Operation<Void> original) {
        if (!(sender instanceof FabricClientCommandSourceStack)) original.call(sender);
    }

    @WrapOperation(method = "findEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/arguments/selector/EntitySelector;findPlayers(Lnet/minecraft/commands/CommandSourceStack;)Ljava/util/List;"))
    private List<? extends Player> overrideFindPlayers(EntitySelector instance, CommandSourceStack sender, Operation<List<ServerPlayer>> original) {
        if (!(sender instanceof FabricClientCommandSourceStack)) return original.call(instance, sender);
        return clientdatacommandupdated$findPlayersClient((FabricClientCommandSourceStack) sender);
    }

    @Inject(
        method = "findEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/CommandSourceStack;getServer()Lnet/minecraft/server/MinecraftServer;",
            ordinal = 0
        ),
        cancellable = true
    )
    private void overrideGetPlayerByName(
        CommandSourceStack sender,
        CallbackInfoReturnable<List<? extends Entity>> cir
    ) {
        if (!(sender instanceof FabricClientCommandSourceStack)) return;
        cir.setReturnValue(this.clientdatacommandupdated$getMatchingPlayerByNameAsList((FabricClientCommandSourceStack) sender));
    }

    @Inject(
        method = "findEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/CommandSourceStack;getServer()Lnet/minecraft/server/MinecraftServer;",
            ordinal = 1
        ),
        cancellable = true
    )
    private void overrideGetEntityByUUID(
        CommandSourceStack sender,
        CallbackInfoReturnable<List<? extends Entity>> cir
    ) {
        if (!(sender instanceof FabricClientCommandSourceStack)) return;
        Entity entity = this.clientdatacommandupdated$getMatchingEntityByUUID((FabricClientCommandSourceStack) sender);
        cir.setReturnValue(entity == null ? List.of() : List.of(entity));
    }

    @Inject(
        method = "findEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/EntitySelector;addEntities(Ljava/util/List;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)V",
            ordinal = 0
        ),
        cancellable = true
    )
    private void overrideAddEntities(
        CommandSourceStack sender,
        CallbackInfoReturnable<List<? extends Entity>> cir,
        @Local(name = "result") List<Entity> result,
        @Local(name = "absoluteAabb") AABB absoluteAabb,
        @Local(name = "predicate") Predicate<Entity> predicate,
        @Local(name = "pos") Vec3 pos
    ) {
        if (!(sender instanceof FabricClientCommandSourceStack)) return;
        this.clientdatacommandupdated$addEntities(result, (FabricClientCommandSourceStack) sender, absoluteAabb, predicate);
        cir.setReturnValue(this.sortAndLimit(pos, result));
    }

    @Inject(
        method = "findEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/CommandSourceStack;getServer()Lnet/minecraft/server/MinecraftServer;",
            ordinal = 2
        ),
        cancellable = true
    )
    private void overrideGetAllEntities(
        CommandSourceStack sender,
        CallbackInfoReturnable<List<? extends Entity>> cir,
        @Local(name = "result") List<Entity> result,
        @Local(name = "absoluteAabb") AABB absoluteAabb,
        @Local(name = "predicate") Predicate<Entity> predicate,
        @Local(name = "pos") Vec3 pos
    ) {
        if (!(sender instanceof FabricClientCommandSourceStack)) return;
        this.clientdatacommandupdated$addEntities(result, (FabricClientCommandSourceStack) sender, absoluteAabb, predicate);
        cir.setReturnValue(this.sortAndLimit(pos, result));
    }

    @Override
    public Player clientdatacommandupdated$findSinglePlayerClient(FabricClientCommandSourceStack sender) throws CommandSyntaxException {
        List<? extends Player> players = this.clientdatacommandupdated$findPlayersClient(sender);
        if (players.size() != 1) throw EntityArgument.NO_PLAYERS_FOUND.create();
        else return players.getFirst();
    }

    @Override
    public @Nullable String clientdatacommandupdated$playerName() {
        return this.playerName;
    }

    @Override
    public @Nullable UUID clientdatacommandupdated$entityUUID() {
        return this.entityUUID;
    }

    @Override
    public List<? extends Player> clientdatacommandupdated$findPlayersClient(FabricClientCommandSourceStack sender) {
        if (this.playerName != null) return this.clientdatacommandupdated$getMatchingPlayerByNameAsList(sender);

        if (this.entityUUID != null) {
            Entity entity = this.clientdatacommandupdated$getMatchingEntityByUUID(sender);
            return (entity instanceof Player player) ? List.of(player) : List.of();
        }

        Vec3 pos = this.position.apply(sender.getPosition());
        AABB absoluteAabb = this.getAbsoluteAabb(pos);
        Predicate<Entity> predicate = this.getPredicate(pos, absoluteAabb, null);
        if (this.currentEntity) return sender.getEntity() instanceof Player player && predicate.test(player) ? List.of(player) : List.of();

        List<Player> result = new ObjectArrayList<>();
        for (Player player : sender.getClientSource().getLevel().players()) {
            if (predicate.test(player)) {
                result.add(player);
                if (result.size() >= this.getResultLimit()) {
                    return result;
                }
            }
        }

        return this.sortAndLimit(pos, result);
    }

    @Unique
    private void clientdatacommandupdated$addEntities(List<Entity> result, FabricClientCommandSourceStack sender, AABB absoluteAabb, Predicate<Entity> predicate) {
        if (absoluteAabb != null) {
            sender
                .getClientSource()
                .getLevel()
                .getEntities(
                    this.type,
                    absoluteAabb,
                    predicate,
                    result,
                    this.getResultLimit()
                );
        } else {
            for (Entity entity : sender.getClientSource().getLevel().entitiesForRendering()) {
                if (result.size() >= this.getResultLimit()) break;
                if (predicate.test(entity)) {
                    result.add(entity);
                }
            }
        }
    }

    @Unique
    private List<? extends Player> clientdatacommandupdated$getMatchingPlayerByNameAsList(FabricClientCommandSourceStack sender) {
        return sender
            .getClientSource()
            .getLevel()
            .players()
            .stream()
            .filter(it -> it.getGameProfile().name().equalsIgnoreCase(this.playerName))
            .limit(1)
            .toList();
    }

    @Unique
    private @Nullable Entity clientdatacommandupdated$getMatchingEntityByUUID(FabricClientCommandSourceStack sender) {
        return sender.getClientSource().getLevel().getEntity(Objects.requireNonNull(this.entityUUID));
    }
}
