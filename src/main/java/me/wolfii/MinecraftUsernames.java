package me.wolfii;

import java.util.regex.Pattern;

/**
 * Java Edition username rules: 3–16 characters, {@code [A-Za-z0-9_]}.
 */
public final class MinecraftUsernames {
	public static final Pattern PATTERN = Pattern.compile("[a-zA-Z0-9_]{3,16}");

	private MinecraftUsernames() {
	}

	public static boolean isValid(String name) {
		return name != null && PATTERN.matcher(name).matches();
	}
}
