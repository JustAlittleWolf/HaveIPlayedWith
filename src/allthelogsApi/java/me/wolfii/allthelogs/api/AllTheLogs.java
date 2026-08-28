package me.wolfii.allthelogs.api;

/**
 * Compile-only stub of AllTheLogs' public entry point. The real class is loaded at runtime
 * when the AllTheLogs mod is present; these stubs are not packaged into the jar.
 */
public final class AllTheLogs {
	private AllTheLogs() {
	}

	public static LogDatabase database() {
		throw new AssertionError("AllTheLogs stub");
	}
}
