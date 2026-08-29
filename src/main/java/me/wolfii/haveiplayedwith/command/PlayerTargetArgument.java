package me.wolfii.haveiplayedwith.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.wolfii.haveiplayedwith.profile.ProfileApi;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

final class PlayerTargetArgument implements ArgumentType<PlayerTargetArgument.PlayerTarget> {
    private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(
        value -> Component.literal("Not a player name or UUID: " + value)
    );
    private static final Collection<String> EXAMPLES = List.of(
        "Steve",
        "alex",
        "069a79f4-44e9-4726-a5be-fca90e38aaf5"
    );
    private static final Pattern DASHED_UUID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    private static final Pattern FLAT_UUID = Pattern.compile("[0-9a-fA-F]{32}");

    private PlayerTargetArgument() {
    }

    public static PlayerTargetArgument player() {
        return new PlayerTargetArgument();
    }

    public static PlayerTarget get(CommandContext<?> context, String name) {
        return context.getArgument(name, PlayerTarget.class);
    }

    static boolean isUuidToken(String token) {
        return token != null && (DASHED_UUID.matcher(token).matches() || FLAT_UUID.matcher(token).matches());
    }

    /**
     * Hyphenated tokens and 32-character hex strings are treated as UUIDs, not names, so a
     * malformed one is reported as a bad UUID rather than looked up as a username.
     */
    static boolean looksLikeUuid(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return token.indexOf('-') >= 0 || FLAT_UUID.matcher(token).matches();
    }

    static PlayerTarget parseToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("empty player target");
        }
        if (isUuidToken(token)) {
            return new PlayerTarget(null, ProfileApi.parseUuid(token));
        }
        if (looksLikeUuid(token)) {
            throw new IllegalArgumentException("invalid uuid: " + token);
        }
        return new PlayerTarget(token, null);
    }

    @Override
    public PlayerTarget parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String token = reader.readUnquotedString();
        if (token.isEmpty()) {
            reader.setCursor(start);
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "player");
        }
        try {
            return parseToken(token);
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

    record PlayerTarget(String name, UUID uuid) {
    }
}
