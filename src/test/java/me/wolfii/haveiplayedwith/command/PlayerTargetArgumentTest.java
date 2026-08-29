package me.wolfii.haveiplayedwith.command;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTargetArgumentTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void parsesJavaUsername() {
        PlayerTargetArgument.PlayerTarget target = PlayerTargetArgument.parseToken("JustAlittleWolf");
        assertEquals("JustAlittleWolf", target.name());
        assertNull(target.uuid());
    }

    @Test
    void parsesDashedUuid() {
        PlayerTargetArgument.PlayerTarget target = PlayerTargetArgument.parseToken("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertNull(target.name());
        assertEquals(NOTCH, target.uuid());
    }

    @Test
    void parsesFlatUuid() {
        PlayerTargetArgument.PlayerTarget target = PlayerTargetArgument.parseToken("069a79f444e94726a5befca90e38aaf5");
        assertEquals(NOTCH, target.uuid());
    }

    @Test
    void rejectsMalformedHyphenatedToken() {
        assertTrue(PlayerTargetArgument.looksLikeUuid("not-a-uuid"));
        assertFalse(PlayerTargetArgument.isUuidToken("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> PlayerTargetArgument.parseToken("not-a-uuid"));
    }

    @Test
    void rejectsEmptyToken() {
        assertThrows(IllegalArgumentException.class, () -> PlayerTargetArgument.parseToken(""));
    }
}
