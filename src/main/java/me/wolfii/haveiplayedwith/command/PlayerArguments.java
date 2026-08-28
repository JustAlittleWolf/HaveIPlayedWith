package me.wolfii.haveiplayedwith.command;

import me.wolfii.haveiplayedwith.mojang.MojangProfileApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

final class PlayerArguments {
    private static final Pattern DASHED_UUID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    private static final Pattern FLAT_UUID = Pattern.compile("[0-9a-fA-F]{32}");

    private PlayerArguments() {
    }

    static boolean isUuidToken(String token) {
        return token != null && (DASHED_UUID.matcher(token).matches() || FLAT_UUID.matcher(token).matches());
    }

    /**
     * Hyphenated tokens and 32-character hex strings are treated as UUIDs, not names.
     */
    static boolean looksLikeUuid(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (token.indexOf('-') >= 0) {
            return true;
        }
        return token.length() == 32 && token.chars().allMatch(ch -> Character.digit(ch, 16) >= 0);
    }

    static ResolvedPlayer parseToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("empty player target");
        }
        if (isUuidToken(token)) {
            return new ResolvedPlayer(null, MojangProfileApi.parseUuid(token));
        }
        if (looksLikeUuid(token)) {
            throw new IllegalArgumentException("invalid uuid: " + token);
        }
        return new ResolvedPlayer(token, null);
    }

    static UUID uuidFromTab(String name) {
        Minecraft client = Minecraft.getInstance();
        AtomicReference<UUID> found = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        client.execute(() -> {
            try {
                ClientPacketListener connection = client.getConnection();
                if (connection != null) {
                    for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                        if (name.equalsIgnoreCase(info.getProfile().name())) {
                            found.set(info.getProfile().id());
                            break;
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found.get();
    }

    record ResolvedPlayer(String name, UUID uuid) {
    }
}
