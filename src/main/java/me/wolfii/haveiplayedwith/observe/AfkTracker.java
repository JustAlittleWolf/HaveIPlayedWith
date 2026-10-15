package me.wolfii.haveiplayedwith.observe;

/**
 * Turns a stream of "the client did something" ticks into an AFK flag.
 * Idle time stops climbing once the threshold is reached.
 */
final class AfkTracker {
    /** One minute of client ticks with no input. */
    static final int IDLE_TICKS = 20 * 60;

    private final int idleTicksLimit;
    private int idleTicks;

    AfkTracker() {
        this(IDLE_TICKS);
    }

    AfkTracker(int idleTicksLimit) {
        this.idleTicksLimit = idleTicksLimit;
    }

    boolean tick(boolean active) {
        if (active) {
            idleTicks = 0;
            return false;
        }
        if (idleTicks < idleTicksLimit) {
            idleTicks++;
        }
        return afk();
    }

    boolean afk() {
        return idleTicks >= idleTicksLimit;
    }
}
