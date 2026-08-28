package me.wolfii.haveiplayedwith.command;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerArgumentsTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void parsesJavaUsername() {
        PlayerArguments.ResolvedPlayer target = PlayerArguments.parseToken("JustAlittleWolf");
        assertEquals("JustAlittleWolf", target.name());
        assertNull(target.uuid());
    }

    @Test
    void parsesDashedUuid() {
        PlayerArguments.ResolvedPlayer target = PlayerArguments.parseToken("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertNull(target.name());
        assertEquals(NOTCH, target.uuid());
    }

    @Test
    void parsesFlatUuid() {
        PlayerArguments.ResolvedPlayer target = PlayerArguments.parseToken("069a79f444e94726a5befca90e38aaf5");
        assertEquals(NOTCH, target.uuid());
    }

    @Test
    void rejectsMalformedHyphenatedToken() {
        assertTrue(PlayerArguments.looksLikeUuid("not-a-uuid"));
        assertFalse(PlayerArguments.isUuidToken("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> PlayerArguments.parseToken("not-a-uuid"));
    }

    @Test
    void rejectsEmptyToken() {
        assertThrows(IllegalArgumentException.class, () -> PlayerArguments.parseToken(""));
    }
}
