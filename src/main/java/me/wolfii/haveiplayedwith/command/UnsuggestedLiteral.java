package me.wolfii.haveiplayedwith.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * A literal that can be typed and executed, but is never offered as a tab suggestion.
 * Used so {@code /playernote confirm} still works while player-name suggestions stay
 * limited to people actually on the tab list (including a player named confirm).
 */
final class UnsuggestedLiteral {
    private UnsuggestedLiteral() {
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> of(String name) {
        return new Builder(name);
    }

    private static final class Builder extends LiteralArgumentBuilder<FabricClientCommandSource> {
        private Builder(String literal) {
            super(literal);
        }

        @Override
        public LiteralCommandNode<FabricClientCommandSource> build() {
            LiteralCommandNode<FabricClientCommandSource> built = super.build();
            Hidden node = new Hidden(
                built.getLiteral(),
                built.getCommand(),
                built.getRequirement(),
                built.getRedirect(),
                built.getRedirectModifier(),
                built.isFork()
            );
            for (CommandNode<FabricClientCommandSource> child : built.getChildren()) {
                node.addChild(child);
            }
            return node;
        }
    }

    private static final class Hidden extends LiteralCommandNode<FabricClientCommandSource> {
        private Hidden(
            String literal,
            Command<FabricClientCommandSource> command,
            Predicate<FabricClientCommandSource> requirement,
            CommandNode<FabricClientCommandSource> redirect,
            RedirectModifier<FabricClientCommandSource> modifier,
            boolean forks
        ) {
            super(literal, command, requirement, redirect, modifier, forks);
        }

        @Override
        public CompletableFuture<Suggestions> listSuggestions(
            CommandContext<FabricClientCommandSource> context,
            SuggestionsBuilder builder
        ) {
            return Suggestions.empty();
        }
    }
}
