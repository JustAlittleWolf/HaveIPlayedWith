package me.wolfii.haveiplayedwith;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Background workers for the mod. Every thread is a daemon so a stuck task can never
 * keep the game from exiting, and every thread is named so it is recognisable in a
 * crash report or profiler.
 */
public final class ModThreads {
    private ModThreads() {
    }

    public static ExecutorService singleWorker(String name) {
        return Executors.newSingleThreadExecutor(thread(name));
    }

    public static ScheduledExecutorService singleScheduledWorker(String name) {
        return Executors.newSingleThreadScheduledExecutor(thread(name));
    }

    private static ThreadFactory thread(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, HaveIPlayedWith.MOD_ID + "-" + name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
