package me.wolfii.haveiplayedwith.importing;

import me.wolfii.haveiplayedwith.MinecraftUsernames;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a likely Minecraft username out of a chat log line.
 *
 * <p>Formats:
 * <ul>
 *   <li>{@code <Name>} at the start of the line</li>
 *   <li>{@code Name joined} / {@code Name left} when the line has no {@code :}</li>
 *   <li>{@code Name:} (optionally after {@code [...]} ranks) followed by a real chat body</li>
 * </ul>
 */
public final class UsernameExtractor {
    private static final Pattern JOIN_LEAVE = Pattern.compile("^([a-zA-Z0-9_]{3,16}) (?:joined|left)\\b");
    private static final Pattern NAME_THEN_COLON = Pattern.compile("([a-zA-Z0-9_]{3,16}):");

    private UsernameExtractor() {
    }

    public static Optional<String> extract(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String line = stripLegacy(message).strip();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> angled = angledName(line);
        if (angled.isPresent()) {
            return angled;
        }
        if (!line.contains(":")) {
            Matcher join = JOIN_LEAVE.matcher(line);
            if (join.find()) {
                return Optional.of(join.group(1));
            }
            return Optional.empty();
        }
        return colonName(line);
    }

    public static boolean isUsername(String name) {
        return MinecraftUsernames.isValid(name);
    }

    private static Optional<String> angledName(String line) {
        if (!line.startsWith("<")) {
            return Optional.empty();
        }
        int end = line.indexOf('>');
        if (end < 2) {
            return Optional.empty();
        }
        String name = line.substring(1, end);
        return isUsername(name) ? Optional.of(name) : Optional.empty();
    }

    private static Optional<String> colonName(String line) {
        Matcher matcher = NAME_THEN_COLON.matcher(line);
        while (matcher.find()) {
            if (hasAsciiLetterOutsideBrackets(line, 0, matcher.start())) {
                continue;
            }
            if (!isChatBody(line.substring(matcher.end()))) {
                continue;
            }
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    static boolean isChatBody(String rest) {
        String trimmed = rest.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] words = trimmed.split("\\s+");
        if (words.length < 5) {
            return false;
        }
        int withLetter = 0;
        for (String word : words) {
            if (containsAsciiLetter(word)) {
                withLetter++;
            }
        }
        return withLetter >= 3;
    }

    static boolean hasAsciiLetterOutsideBrackets(String line, int start, int end) {
        boolean inBracket = false;
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (c == '[') {
                inBracket = true;
            } else if (c == ']') {
                inBracket = false;
            } else if (!inBracket && isAsciiLetter(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAsciiLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (isAsciiLetter(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String stripLegacy(String message) {
        return message.replaceAll("§.", "");
    }
}
