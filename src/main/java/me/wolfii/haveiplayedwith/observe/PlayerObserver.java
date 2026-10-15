package me.wolfii.haveiplayedwith.observe;

import net.minecraft.client.Minecraft;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Collects newly due play sightings and submits one batch per tick. */
final class PlayerObserver extends PlayerMinuteObserver {
    private final Predicate<List<ObservationManager.Sighting>> enqueueSightings;

    PlayerObserver(Predicate<List<ObservationManager.Sighting>> enqueueSightings) {
        this.enqueueSightings = enqueueSightings;
    }

    @Override
    void tick(Minecraft client, long epochMinute, String serverId) {
        prune(epochMinute);
        LocalDate day = LocalDate.now();
        List<ObservationManager.Sighting> batch = new ArrayList<>();
        observeAllPlayers(client, epochMinute, profile -> {
            batch.add(new ObservationManager.Sighting(profile.id(), profile.name(), day, epochMinute, serverId));
            return true;
        });
        if (!batch.isEmpty() && !enqueueSightings.test(batch)) {
            for (ObservationManager.Sighting sighting : batch) {
                forgetQueuedMinute(sighting.uuid(), epochMinute);
            }
        }
    }
}
