package me.wolfii.haveiplayedwith.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class PlayerTargetArgument implements ArgumentType<PlayerArguments.ResolvedPlayer> {
    private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(
        value -> Component.literal("Not a player name or UUID: " + value)
    );
    private static final Collection<String> EXAMPLES = List.of(
        "Steve",
        "alex",
        "069a79f4-44e9-4726-a5be-fca90e38aaf5"
    );

    private PlayerTargetArgument() {
    }

    public static PlayerTargetArgument player() {
        return new PlayerTargetArgument();
    }

    public static PlayerArguments.ResolvedPlayer get(CommandContext<?> context, String name) {
        return context.getArgument(name, PlayerArguments.ResolvedPlayer.class);
    }

    @Override
    public PlayerArguments.ResolvedPlayer parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String token = reader.readUnquotedString();
        if (token.isEmpty()) {
            reader.setCursor(start);
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "player");
        }
        try {
            return PlayerArguments.parseToken(token);
        } catch (IllegalArgumentException e) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader, token);
        }
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (context.getSource() instanceof SharedSuggestionProvider provider) {
            return SharedSuggestionProvider.suggest(provider.getOnlinePlayerNames(), builder);
        }
        return Suggestions.empty();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
