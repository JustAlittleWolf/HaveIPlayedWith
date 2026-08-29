package me.wolfii.haveiplayedwith;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one logger the whole mod writes to. Kept apart from {@link HaveIPlayedWith} so
 * classes without a Minecraft client can log too.
 */
public final class ModLog {
    public static final Logger LOGGER = LoggerFactory.getLogger(HaveIPlayedWith.MOD_ID);

    private ModLog() {
    }
}
