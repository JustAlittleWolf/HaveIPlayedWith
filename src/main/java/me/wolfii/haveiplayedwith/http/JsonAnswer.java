package me.wolfii.haveiplayedwith.http;

/**
 * What a {@link JsonApi} lookup came back with. Status codes and transport failures are
 * already folded in, so callers only decide between "here is a body", "the service says
 * there is nothing here" and "no answer, ask again later".
 */
public sealed interface JsonAnswer {
    Missing MISSING = new Missing();
    Unavailable UNAVAILABLE = new Unavailable();

    /** The service answered with a JSON body to parse. */
    record Body(String json) implements JsonAnswer {
    }

    /** The service answered that it holds nothing under this key. */
    record Missing() implements JsonAnswer {
    }

    /** Rate limited, failing or unreachable. Nothing here is worth caching as a miss. */
    record Unavailable() implements JsonAnswer {
    }
}
