package me.wolfii.observe;

import com.mojang.authlib.GameProfile;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.net.MojangClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches the tab list, nearby players, and the local player. Minutes are credited after a
 * Mojang username match, off the client thread.
 */
public final class PlayerObserver {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	private static final int MAX_BUFFER = 250;

	private record Sighting(UUID uuid, String username, LocalDate day, long epochMinute, String sessionId) {
	}

	private final PlayerDatabase database;
	private final MojangClient mojang;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-observe");
		thread.setDaemon(true);
		return thread;
	});
	private final ConcurrentHashMap<UUID, Sighting> pending = new ConcurrentHashMap<>();
	private final ArrayBlockingQueue<UUID> queue = new ArrayBlockingQueue<>(MAX_BUFFER);
	private final ConcurrentHashMap<UUID, Long> creditedMinute = new ConcurrentHashMap<>();
	private volatile String sessionId;
	private int ticks;

	public PlayerObserver(PlayerDatabase database, MojangClient mojang) {
		this.database = database;
		this.mojang = mojang;
		worker.execute(this::processLoop);
	}

	public void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sessionId = UUID.randomUUID().toString());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			sessionId = null;
			creditedMinute.clear();
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	public void close() {
		worker.shutdownNow();
	}

	private void tick(Minecraft client) {
		if (client.level == null || client.player == null || client.getConnection() == null) {
			return;
		}
		ticks++;
		if (ticks % 20 != 0) {
			return;
		}
		String session = sessionId;
		if (session == null) {
			session = "live";
		}
		long epochMinute = System.currentTimeMillis() / 60_000L;
		LocalDate day = LocalDate.now();
		Set<UUID> seen = new HashSet<>();
		ClientPacketListener connection = client.getConnection();
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			offer(profile(info), day, epochMinute, session, seen);
		}
		for (AbstractClientPlayer player : client.level.players()) {
			offer(player.getGameProfile(), day, epochMinute, session, seen);
		}
		LocalPlayer self = client.player;
		offer(self.getGameProfile(), day, epochMinute, session, seen);
	}

	private void offer(GameProfile profile, LocalDate day, long epochMinute, String session, Set<UUID> seen) {
		if (profile == null) {
			return;
		}
		UUID uuid = profileId(profile);
		String name = profileName(profile);
		if (uuid == null || name == null || name.isBlank()) {
			return;
		}
		if (!seen.add(uuid)) {
			return;
		}
		Long last = creditedMinute.get(uuid);
		if (last != null && last == epochMinute) {
			return;
		}
		if (mojang.isFreshMismatch(uuid, name)) {
			return;
		}
		if (!mojang.needsFetch(uuid, name)) {
			credit(new Sighting(uuid, name, day, epochMinute, session));
			return;
		}
		Sighting sighting = new Sighting(uuid, name, day, epochMinute, session);
		Sighting previous = pending.put(uuid, sighting);
		if (previous == null && !queue.offer(uuid)) {
			pending.remove(uuid, sighting);
		}
	}

	private void processLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				UUID uuid = queue.take();
				Sighting sighting = pending.remove(uuid);
				if (sighting == null) {
					continue;
				}
				var profile = mojang.lookupUuid(uuid);
				if (profile.isEmpty() || !profile.get().username().equalsIgnoreCase(sighting.username())) {
					continue;
				}
				if (!profile.get().username().equals(sighting.username())) {
					database.applyMojangUsername(uuid, profile.get().username(), java.time.Instant.now());
				}
				credit(new Sighting(uuid, profile.get().username(), sighting.day(), sighting.epochMinute(), sighting.sessionId()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				LOGGER.debug("Player observation failed", e);
			}
		}
	}

	private void credit(Sighting sighting) {
		Long last = creditedMinute.put(sighting.uuid(), sighting.epochMinute());
		if (last != null && last == sighting.epochMinute()) {
			return;
		}
		database.recordLivePlay(sighting.uuid(), sighting.username(), sighting.day(), "live:" + sighting.sessionId());
	}

	private static UUID profileId(GameProfile profile) {
		return profile.id();
	}

	private static String profileName(GameProfile profile) {
		return profile.name();
	}

	private static GameProfile profile(PlayerInfo info) {
		return info.getProfile();
	}
}
