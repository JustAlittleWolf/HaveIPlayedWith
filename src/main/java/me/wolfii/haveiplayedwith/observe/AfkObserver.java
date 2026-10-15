package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.mixin.FramerateLimitTrackerAccessor;
import me.wolfii.haveiplayedwith.store.PlayerStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Util;

/**
 * Counts a minute as AFK when the client has had no input for {@link #AFK_AFTER_MS}.
 * That window belongs to this mod. The write only adds an AFK minute.
 */
final class AfkObserver extends MinuteObserver {
    static final long AFK_AFTER_MS = 60_000L;

    AfkObserver(PlayerStore players, MinuteScheduler minutes) {
        super(players, minutes);
    }

    static boolean inactive(long latestInputMillis, long nowMillis) {
        return nowMillis - latestInputMillis >= AFK_AFTER_MS;
    }

    static boolean clientAfk(Minecraft client) {
        long latestInput = ((FramerateLimitTrackerAccessor) client.getFramerateLimitTracker()).haveiplayedwith$latestInputTime();
        return inactive(latestInput, Util.getMillis());
    }

    @Override
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
            if (!offer(info.getProfile(), epochMinute, players::recordAfkMinute)) {
                return;
            }
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (!offer(player.getGameProfile(), epochMinute, players::recordAfkMinute)) {
                return;
            }
        }
        offer(client.player.getGameProfile(), epochMinute, players::recordAfkMinute);
    }
}
