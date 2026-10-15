package me.wolfii.haveiplayedwith.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.haveiplayedwith.MinecraftUsernames;
import me.wolfii.haveiplayedwith.mixin.FramerateLimitTrackerAccessor;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Util;

import java.util.UUID;

/**
 * Counts a minute as AFK when the client has had no input for {@link #AFK_AFTER_MS}.
 * That window belongs to this mod. Each player is written once per calendar minute,
 * and the write only adds an AFK minute.
 */
final class AfkObserver {
    static final long AFK_AFTER_MS = 60_000L;
    private static final long MEMORY_MINUTES = 60;

    private final PlayerStore players;
    private final MinuteScheduler minutes;
    private final NotedMinutes noted = new NotedMinutes();
    private long currentMinute = Long.MIN_VALUE;

    AfkObserver(PlayerStore players, MinuteScheduler minutes) {
        this.players = players;
        this.minutes = minutes;
    }

    static boolean inactive(long latestInputMillis, long nowMillis) {
        return nowMillis - latestInputMillis >= AFK_AFTER_MS;
    }

    static boolean clientAfk(Minecraft client) {
        long latestInput = ((FramerateLimitTrackerAccessor) client.getFramerateLimitTracker()).haveiplayedwith$latestInputTime();
        return inactive(latestInput, Util.getMillis());
    }

    void tick(Minecraft client, long epochMinute) {
        if (client.level == null || client.player == null || client.getConnection() == null) {
            return;
        }
        prune(epochMinute);
        if (!clientAfk(client)) {
            return;
        }
        ClientPacketListener connection = client.getConnection();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (!offer(info.getProfile(), epochMinute)) {
                return;
            }
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (!offer(player.getGameProfile(), epochMinute)) {
                return;
            }
        }
        offer(client.player.getGameProfile(), epochMinute);
    }

    void clear() {
        noted.clear();
        currentMinute = Long.MIN_VALUE;
    }

    private void prune(long epochMinute) {
        if (epochMinute == currentMinute) {
            return;
        }
        currentMinute = epochMinute;
        noted.pruneBefore(epochMinute - MEMORY_MINUTES);
    }

    /** @return {@code false} when the write queue is full and this tick should stop */
    private boolean offer(GameProfile profile, long epochMinute) {
        if (profile == null) {
            return true;
        }
        UUID uuid = profile.id();
        if (uuid == null || !MinecraftUsernames.isValid(profile.name())) {
            return true;
        }
        if (noted.noted(uuid, epochMinute)) {
            return true;
        }
        if (!minutes.offer(() -> players.recordAfkMinute(uuid))) {
            return false;
        }
        noted.note(uuid, epochMinute);
        return true;
    }
}
