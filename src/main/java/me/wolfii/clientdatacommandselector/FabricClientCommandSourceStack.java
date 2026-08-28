package me.wolfii.clientdatacommandselector;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class FabricClientCommandSourceStack extends CommandSourceStack {

    private final FabricClientCommandSource clientSource;

    public FabricClientCommandSourceStack(FabricClientCommandSource clientSource) {
        super(
            CommandSource.NULL,
            clientSource.getPosition(),
            clientSource.getRotation(),
            null,
            clientSource.permissions(),
            clientSource.getEntity().getPlainTextName(),
            clientSource.getEntity().getDisplayName(),
            null,
            clientSource.getEntity()
        );
        this.clientSource = clientSource;
    }

    public static FabricClientCommandSourceStack fromMinecraft(Minecraft minecraft) {
        return new FabricClientCommandSourceStack(new FabricClientCommandSource() {
            @Override
            public void sendFeedback(@NonNull Component message) {
            }

            @Override
            public void sendError(@NonNull Component message) {
            }

            @Override
            public @NonNull Minecraft getClient() {
                return minecraft;
            }

            @Override
            public @NonNull LocalPlayer getPlayer() {
                return Objects.requireNonNull(minecraft.player);
            }

            @Override
            public @NonNull ClientLevel getLevel() {
                return Objects.requireNonNull(minecraft.level);
            }

            @Override
            public boolean attended() {
                return false;
            }

            @Override
            public @NonNull Collection<String> getOnlinePlayerNames() {
                return List.of();
            }

            @Override
            public @NonNull Collection<String> getAllTeams() {
                return List.of();
            }

            @Override
            public @NonNull Stream<Identifier> getAvailableSounds() {
                return Stream.empty();
            }

            @Override
            public @NonNull CompletableFuture<Suggestions> customSuggestion(@NonNull CommandContext<?> context) {
                return Suggestions.empty();
            }

            @Override
            public @NonNull Set<ResourceKey<Level>> levels() {
                return Set.of();
            }

            @Override
            public @NonNull RegistryAccess registryAccess() {
                return RegistryAccess.EMPTY;
            }

            @Override
            public @NonNull FeatureFlagSet enabledFeatures() {
                return getLevel().enabledFeatures();
            }

            @Override
            public @NonNull CompletableFuture<Suggestions> suggestRegistryElements(ResourceKey<? extends Registry<?>> key, ElementSuggestionType elements, SuggestionsBuilder builder, CommandContext<?> context) {
                return Suggestions.empty();
            }

            @Override
            public @NonNull PermissionSet permissions() {
                return Objects.requireNonNull(minecraft.player).permissions();
            }
        });
    }

    public FabricClientCommandSource getClientSource() {
        return clientSource;
    }

    @Override
    public @NonNull FeatureFlagSet enabledFeatures() {
        return clientSource.getLevel().enabledFeatures();
    }
}
