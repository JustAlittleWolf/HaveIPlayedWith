package me.wolfii.haveiplayedwith.chat;

import me.wolfii.haveiplayedwith.store.PlayerSnapshot;
import me.wolfii.haveiplayedwith.store.SeenName;
import me.wolfii.haveiplayedwith.store.ServerPlay;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChatMessagesTest {
    private static final UUID STEVE = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    @Test
    void unplayedUsernamesAreItalic() {
        Component notPlayed = QueryMessages.notPlayedWith("Alex", STEVE);
        assertUsername(arg(notPlayed, 0), "Alex", true);
    }

    @Test
    void usernamesAreClickableAndShareAColor() {
        Component played = QueryMessages.playedWith(snapshot());
        Component name = arg(played, 0);
        assertUsername(name, "Alex", false);

        Component past = arg(QueryMessages.pastNames(snapshot()), 0);
        Component pastName = firstSibling(past);
        assertUsername(pastName, "Steve", false);

        Component unknown = arg(QueryMessages.unknownAccount("Notch"), 0);
        assertUsername(unknown, "Notch", false);

        Component renamed = RenameMessages.playerRenamed("Steve", "Alex", STEVE);
        assertUsername(arg(renamed, 0), "Steve", false);
        assertUsername(arg(renamed, 1), "Alex", false);
    }

    @Test
    void wordingIsGrayAndHoverOnlyDataIsNotUnderlined() {
        Component played = QueryMessages.playedWith(snapshot());
        assertEquals(TextColor.GRAY.getValue(), played.getStyle().getColor().getValue());

        assertEquals(3, args(played).length);

        Component duration = arg(played, 1);
        assertEquals(TextColor.GRAY.getValue(), duration.getStyle().getColor().getValue());
        assertEquals(false, duration.getStyle().isUnderlined());
        assertEquals(ChatStyle.DURATION, color(arg(duration, 0)));
        HoverEvent.ShowText durationHover = assertInstanceOf(HoverEvent.ShowText.class, duration.getStyle().getHoverEvent());
        assertEquals("haveiplayedwith.duration.hover", key(firstSibling(durationHover.value())));
        Component lastPlayed = durationHover.value().getSiblings().get(2);
        assertEquals("haveiplayedwith.query.last_played", key(lastPlayed));
        assertEquals(ChatStyle.COUNT, color(arg(lastPlayed, 0)));
        assertEquals("2026-08-01", literal(arg(lastPlayed, 0)));

        Component days = arg(played, 2);
        assertEquals(false, days.getStyle().isUnderlined());
        assertEquals(ChatStyle.COUNT, color(arg(days, 0)));
        HoverEvent.ShowText daysHover = assertInstanceOf(HoverEvent.ShowText.class, days.getStyle().getHoverEvent());
        Component sessions = firstSibling(daysHover.value());
        assertEquals("haveiplayedwith.query.sessions.across", key(sessions));
        assertEquals("haveiplayedwith.query.sessions", key(arg(sessions, 0)));
        assertEquals(ChatStyle.COUNT, color(arg(arg(sessions, 0), 0)));
        Component mostServer = daysHover.value().getSiblings().get(2);
        assertEquals("haveiplayedwith.query.most_server", key(mostServer));
        assertEquals("hypixel.net", literal(arg(mostServer, 0)));
        assertEquals(ChatStyle.SERVER, color(arg(mostServer, 0)));

        Component seenOn = QueryMessages.seenOn(snapshot());
        Component serverList = arg(seenOn, 0);
        Component firstServer = firstSibling(serverList);
        Component serverId = arg(firstServer, 0);
        assertEquals("hypixel.net", literal(serverId));
        assertEquals(ChatStyle.SERVER, color(serverId));
        assertEquals(false, serverId.getStyle().isUnderlined());
        assertInstanceOf(HoverEvent.ShowText.class, serverId.getStyle().getHoverEvent());
    }

    @Test
    void notesUseTheNoteColorAndCountTimestamps() {
        Component note = NoteMessages.note(snapshot());
        Component body = arg(note, 0);
        assertEquals("builds nice farms", literal(body));
        assertEquals(ChatStyle.NOTE, color(body));
        assertEquals(false, body.getStyle().isUnderlined());
        HoverEvent.ShowText hover = assertInstanceOf(HoverEvent.ShowText.class, body.getStyle().getHoverEvent());
        Component hoverText = hover.value();
        assertEquals("haveiplayedwith.note.taken", key(hoverText));
        assertEquals(ChatStyle.COUNT, color(arg(hoverText, 0)));
    }

    @Test
    void noteStatusMessagesIncludeTheUsername() {
        assertEquals("haveiplayedwith.note.saved", key(NoteMessages.noteSaved("Alex")));
        assertUsername(arg(NoteMessages.noteSaved("Alex"), 0), "Alex", false);
        assertEquals("haveiplayedwith.note.cleared", key(NoteMessages.noteCleared("Alex")));
        assertUsername(arg(NoteMessages.noteCleared("Alex"), 0), "Alex", false);
        assertEquals("haveiplayedwith.note.missing", key(NoteMessages.noteMissing("Alex")));
        assertUsername(arg(NoteMessages.noteMissing("Alex"), 0), "Alex", false);
    }

    private static PlayerSnapshot snapshot() {
        return new PlayerSnapshot(
            STEVE,
            "Alex",
            Optional.of("builds nice farms"),
            Optional.of(Instant.parse("2026-08-02T12:00:00Z")),
            90,
            2,
            2,
            Optional.of(LocalDate.of(2026, 8, 1)),
            List.of(new SeenName("Steve", Instant.parse("2026-08-01T12:00:00Z")), new SeenName("Alex", Instant.parse("2026-08-02T12:00:00Z"))),
            List.of(new ServerPlay("hypixel.net", 60), new ServerPlay("world/Survival", 30))
        );
    }

    private static void assertUsername(Component name, String expected, boolean italic) {
        assertEquals(expected, literal(name));
        assertEquals(ChatStyle.NAME, color(name));
        assertEquals(true, name.getStyle().isUnderlined());
        assertInstanceOf(ClickEvent.OpenUrl.class, name.getStyle().getClickEvent());
        assertEquals(italic, name.getStyle().isItalic());
    }

    private static Component arg(Component component, int index) {
        return (Component) args(component)[index];
    }

    private static Object[] args(Component component) {
        return ((TranslatableContents) component.getContents()).getArgs();
    }

    private static String key(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    private static Component firstSibling(Component component) {
        return component.getSiblings().getFirst();
    }

    private static String literal(Component component) {
        return ((PlainTextContents) component.getContents()).text();
    }

    private static int color(Component component) {
        return color((Object) component);
    }

    private static int color(Object arg) {
        Style style = ((Component) arg).getStyle();
        return style.getColor().getValue();
    }
}
