package me.wolfii.haveiplayedwith.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatTest {
    @Test
    void minutesBelowOneHour() {
        assertTranslation("haveiplayedwith.duration.minute", DurationFormat.compact(1), duration("1"));
        assertTranslation("haveiplayedwith.duration.minutes", DurationFormat.compact(59), duration("59"));
    }

    @Test
    void hoursUntilNinetyNine() {
        assertTranslation("haveiplayedwith.duration.hours", DurationFormat.compact(60), duration("1.0"));
        assertTranslation("haveiplayedwith.duration.hours", DurationFormat.compact(99 * 60), duration("99.0"));
    }

    @Test
    void daysAfterNinetyNineHours() {
        assertTranslation("haveiplayedwith.duration.days", DurationFormat.compact(100 * 60), duration("4.2"));
    }

    @Test
    void hoverOmitsDaysUnderTwentyFourHours() {
        assertTranslation("haveiplayedwith.duration.hover", DurationFormat.hover(5 * 60 + 3), duration("5"), duration("03"));
        Component oneDay = DurationFormat.hover(24 * 60);
        assertEquals("haveiplayedwith.duration.hover.with_days", key(oneDay));
        Object[] args = args(oneDay);
        assertTranslation("haveiplayedwith.duration.day", (Component) args[0], duration("1"));
        assertEquals("00:00", literal(args[1]));
        assertEquals(ChatStyle.DURATION, color(args[1]));
        Component twoDays = DurationFormat.hover(2 * 24 * 60 + 3 * 60 + 15);
        assertEquals("haveiplayedwith.duration.hover.with_days", key(twoDays));
        Object[] twoDayArgs = args(twoDays);
        assertTranslation("haveiplayedwith.duration.days.count", (Component) twoDayArgs[0], duration("2"));
        assertEquals("03:15", literal(twoDayArgs[1]));
        assertEquals(ChatStyle.DURATION, color(twoDayArgs[1]));
    }

    @Test
    void compactWordingIsGrayAndValuesUseDurationColor() {
        Component compact = DurationFormat.compact(59);
        assertEquals(TextColor.GRAY.getValue(), compact.getStyle().getColor().getValue());
        assertEquals(ChatStyle.DURATION, color(args(compact)[0]));
        assertEquals(false, compact.getStyle().isUnderlined());
        assertEquals(false, compact.getStyle().isItalic());
    }

    private static void assertTranslation(String expectedKey, Component component, Component... expectedArgs) {
        assertEquals(expectedKey, key(component));
        Object[] args = args(component);
        assertEquals(expectedArgs.length, args.length);
        for (int i = 0; i < expectedArgs.length; i++) {
            assertEquals(literal(expectedArgs[i]), literal(args[i]));
            assertEquals(color(expectedArgs[i]), color(args[i]));
        }
    }

    private static String key(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    private static Object[] args(Component component) {
        return ((TranslatableContents) component.getContents()).getArgs();
    }

    private static Component duration(String value) {
        return ChatStyle.duration(value);
    }

    private static String literal(Object arg) {
        Component component = (Component) arg;
        return ((PlainTextContents) component.getContents()).text();
    }

    private static int color(Object arg) {
        Style style = ((Component) arg).getStyle();
        return style.getColor().getValue();
    }
}
