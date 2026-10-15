package me.wolfii.haveiplayedwith.observe;

import me.wolfii.haveiplayedwith.store.PlayerActivity;
import me.wolfii.haveiplayedwith.store.PlayerStore;

import java.util.UUID;

/** Queues writes for one independently counted activity. */
abstract class ActivityMinuteObserver extends PlayerMinuteObserver {
    private final PlayerStore playerStore;
    private final ActivityWriteQueue activityWrites;
    private final PlayerActivity activity;

    ActivityMinuteObserver(PlayerStore playerStore, ActivityWriteQueue activityWrites, PlayerActivity activity) {
        this.playerStore = playerStore;
        this.activityWrites = activityWrites;
        this.activity = activity;
    }

    final boolean enqueueMinute(UUID uuid) {
        return activityWrites.offer(() -> playerStore.recordActivityMinute(uuid, activity));
    }
}
