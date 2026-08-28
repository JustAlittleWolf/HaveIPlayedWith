package me.wolfii.clientdatacommandselector.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.wolfii.clientdatacommandselector.ClientEntityArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityArgument.class)
public class EntityArgumentMixin implements ClientEntityArgument {
    @Unique
    private Boolean allowAtSelectorsOverride;

    @Override
    public EntityArgument clientDataCommandUpdated$withAlwaysAllowAtSelectors() {
        allowAtSelectorsOverride = true;
        return (EntityArgument) (Object) this;
    }

    @Override
    public EntityArgument clientDataCommandUpdated$withoutAtSelectors() {
        allowAtSelectorsOverride = false;
        return (EntityArgument) (Object) this;
    }

    @WrapOperation(
        method = "parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lnet/minecraft/commands/arguments/selector/EntitySelector;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/EntitySelectorParser;allowSelectors(Ljava/lang/Object;)Z"
        )
    )
    private <S> boolean parseOverrideShouldAllowAtSelectors(S source, Operation<Boolean> original) {
        if (allowAtSelectorsOverride != null) {
            return allowAtSelectorsOverride;
        }
        return original.call(source);
    }

    @WrapOperation(
        method = "listSuggestions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/permissions/PermissionSet;hasPermission(Lnet/minecraft/server/permissions/Permission;)Z"
        )
    )
    private boolean listSuggestionsOverrideShouldAllowAtSelectors(PermissionSet instance, Permission permission, Operation<Boolean> original) {
        if (allowAtSelectorsOverride != null) {
            return allowAtSelectorsOverride;
        }
        return original.call(instance, permission);
    }
}
