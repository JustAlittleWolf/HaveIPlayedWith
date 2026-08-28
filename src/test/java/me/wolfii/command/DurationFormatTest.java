package me.wolfii.command;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatTest {
    @Test
    void minutesBelowOneHour() {
        assertKey("haveiplayedwith.duration.minute", DurationFormat.compact(1));
        assertTranslation("haveiplayedwith.duration.minutes", DurationFormat.compact(59), 59L);
    }

    @Test
    void hoursUntilNinetyNine() {
        assertTranslation("haveiplayedwith.duration.hours", DurationFormat.compact(60), "1.0");
        assertTranslation("haveiplayedwith.duration.hours", DurationFormat.compact(99 * 60), "99.0");
    }

    @Test
    void daysAfterNinetyNineHours() {
        assertTranslation("haveiplayedwith.duration.days", DurationFormat.compact(100 * 60), "4.2");
    }

    @Test
    void hoverOmitsDaysUnderTwentyFourHours() {
        assertTranslation("haveiplayedwith.duration.hover", DurationFormat.hover(5 * 60 + 3), 5L, "03");
        Component oneDay = DurationFormat.hover(24 * 60);
        assertEquals("haveiplayedwith.duration.hover.with_days", key(oneDay));
        Object[] args = args(oneDay);
        assertEquals("haveiplayedwith.duration.day", key((Component) args[0]));
        assertEquals("00:00", args[1]);
        Component twoDays = DurationFormat.hover(2 * 24 * 60 + 3 * 60 + 15);
        assertEquals("haveiplayedwith.duration.hover.with_days", key(twoDays));
        Object[] twoDayArgs = args(twoDays);
        assertTranslation("haveiplayedwith.duration.days.count", (Component) twoDayArgs[0], 2L);
        assertEquals("03:15", twoDayArgs[1]);
    }

    private static void assertKey(String expected, Component component) {
        assertEquals(expected, key(component));
    }

    private static void assertTranslation(String expectedKey, Component component, Object... expectedArgs) {
        assertEquals(expectedKey, key(component));
        assertArrayEquals(expectedArgs, args(component));
    }

    private static String key(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    private static Object[] args(Component component) {
        return ((TranslatableContents) component.getContents()).getArgs();
    }
}
