package me.wolfii.importing;

import me.wolfii.allthelogs.api.AllTheLogs;
import me.wolfii.allthelogs.api.ChatEntry;
import me.wolfii.allthelogs.api.ChatQuery;
import me.wolfii.allthelogs.api.LogDatabase;
import me.wolfii.command.QueryMessages;
import me.wolfii.db.ImportProgress;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.net.CraftyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AllTheLogsImporter {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	private static final int PAGE_SIZE = 400;
	private static final long SAVE_EVERY = 250;
	/** Most log lines hold no username, so throttle by time rather than by lines walked. */
	private static final long REPORT_INTERVAL_NANOS = 15_000_000_000L;
	private final PlayerDatabase database;
	private final CraftyClient crafty;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-import");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean scheduled = new AtomicBoolean(false);
	private final AtomicBoolean stopRequested = new AtomicBoolean(false);
	private final AtomicBoolean silenced = new AtomicBoolean(false);
	private volatile ImportProgress latest = new ImportProgress(
		ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportProgress.STATUS_STOPPED, false
	);

	public AllTheLogsImporter(PlayerDatabase database, CraftyClient crafty) {
		this.database = database;
		this.crafty = crafty;
	}

	public void resumeIfNeeded() {
		if (!scheduled.compareAndSet(false, true)) {
			return;
		}
		worker.execute(() -> {
			try {
				Optional<ImportProgress> progress = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS);
				progress.ifPresent(stored -> {
					latest = stored;
					silenced.set(stored.silenced());
				});
				if (progress.isPresent() && ImportProgress.STATUS_RUNNING.equals(progress.get().status())) {
					progress(QueryMessages.importStatus("Resuming AllTheLogs import..."));
					runImport(progress.get());
				}
			} finally {
				scheduled.set(false);
			}
		});
	}

	public void startFromCommand() {
		if (!scheduled.compareAndSet(false, true)) {
			chat(QueryMessages.importStatus("AllTheLogs import is already running."));
			return;
		}
		stopRequested.set(false);
		worker.execute(() -> {
			try {
				Optional<ImportProgress> existing = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS);
				ImportProgress start = existing
					.filter(progress -> !ImportProgress.STATUS_DONE.equals(progress.status()))
					.orElseGet(() -> new ImportProgress(
						ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportProgress.STATUS_RUNNING,
						existing.map(ImportProgress::silenced).orElse(false)
					));
				start = start.withStatus(ImportProgress.STATUS_RUNNING);
				chat(QueryMessages.importStatus("Starting AllTheLogs import in the background."));
				runImport(start);
			} finally {
				scheduled.set(false);
			}
		});
	}

	public void stopFromCommand() {
		if (!scheduled.get()) {
			chat(QueryMessages.importStatus("No AllTheLogs import is running."));
			return;
		}
		stopRequested.set(true);
		chat(QueryMessages.importStatus("Stopping AllTheLogs import..."));
	}

	public void toggleSilenceFromCommand() {
		boolean next = !silenced.get();
		silenced.set(next);
		ImportProgress stored = scheduled.get()
			? latest
			: database.importProgress(ImportProgress.SOURCE_ALLTHELOGS).orElse(latest);
		ImportProgress current = stored.withSilenced(next);
		latest = current;
		database.saveImportProgress(current);
		chat(QueryMessages.importStatus(next
			? "AllTheLogs import progress messages silenced."
			: "AllTheLogs import progress messages enabled."));
	}

	public void notifyIfRunning() {
		if (!scheduled.get()) {
			return;
		}
		ImportProgress progress = latest;
		if (progress.total() > 0) {
			progress(QueryMessages.importStatus(
				"AllTheLogs import is still running (" + progress.processed() + "/" + progress.total() + ")."
			));
		} else {
			progress(QueryMessages.importStatus(
				"AllTheLogs import is still running (" + progress.processed() + " messages)."
			));
		}
	}

	private void runImport(ImportProgress start) {
		silenced.set(start.silenced());
		latest = start;
		try {
			waitUntilOpen();
			if (stopRequested.get()) {
				saveStopped(start);
				return;
			}
			LogDatabase logs = AllTheLogs.database();
			if (!logs.isOpen()) {
				progress(QueryMessages.importStatus("AllTheLogs is not ready yet. The import will retry next launch."));
				save(start.withStatus(ImportProgress.STATUS_RUNNING));
				return;
			}
			long total = start.total() >= 0 ? start.total() : logs.countMatches(ChatQuery.all()).join();
			long processed = start.processed();
			LocalDateTime lastTimestamp = start.lastTimestamp();
			long skip = start.skip();
			long lastSave = processed;
			long lastReportAt = System.nanoTime();
			save(new ImportProgress(
				ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_RUNNING, silenced.get()
			));
			while (true) {
				if (stopRequested.get()) {
					saveStopped(latest.withCursor(processed, lastTimestamp, skip));
					return;
				}
				ChatQuery query = ChatQuery.all()
					.withSort(ChatQuery.Sort.ASCENDING)
					.withLimit(PAGE_SIZE);
				if (lastTimestamp != null) {
					query = query.startingAt(lastTimestamp).withSkip(skip);
				}
				List<ChatEntry> page = logs.findEntries(query).join();
				if (page.isEmpty()) {
					break;
				}
				for (ChatEntry entry : page) {
					if (stopRequested.get()) {
						saveStopped(latest.withCursor(processed, lastTimestamp, skip));
						return;
					}
					process(entry);
					processed++;
					if (lastTimestamp != null && entry.timestamp().equals(lastTimestamp)) {
						skip++;
					} else {
						lastTimestamp = entry.timestamp();
						skip = 1;
					}
					if (processed - lastSave >= SAVE_EVERY) {
						lastSave = processed;
						save(new ImportProgress(
							ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_RUNNING, silenced.get()
						));
						long now = System.nanoTime();
						if (now - lastReportAt >= REPORT_INTERVAL_NANOS) {
							lastReportAt = now;
							report(processed, total);
						}
					}
				}
			}
			save(new ImportProgress(
				ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_DONE, silenced.get()
			));
			chat(QueryMessages.importStatus("AllTheLogs import finished (" + processed + " messages)."));
		} catch (Exception e) {
			LOGGER.warn("AllTheLogs import failed", e);
			chat(QueryMessages.importStatus("AllTheLogs import failed. It will resume next launch."));
		}
	}

	private void process(ChatEntry entry) {
		Optional<String> username = UsernameExtractor.extract(entry.message());
		if (username.isEmpty()) {
			return;
		}
		Optional<CraftyClient.Player> player = crafty.lookup(username.get());
		if (player.isEmpty() || !player.get().valid() || player.get().uuid() == null) {
			return;
		}
		Instant at = entry.timestamp().atZone(ZoneId.systemDefault()).toInstant();
		CraftyClient.Player resolved = player.get();
		boolean held;
		if (resolved.history().isEmpty()) {
			held = resolved.currentUsername() != null && resolved.currentUsername().equalsIgnoreCase(username.get());
		} else {
			held = CraftyNameHistory.heldNameAt(resolved.history(), username.get(), at);
		}
		if (!held) {
			return;
		}
		database.recordImportedSighting(
			resolved.uuid(),
			username.get(),
			resolved.currentUsername(),
			entry.timestamp().toLocalDate(),
			ChatLogIds.sessionId(entry.chatLog()),
			at
		);
	}

	private void waitUntilOpen() {
		for (int i = 0; i < 90; i++) {
			if (stopRequested.get() || AllTheLogs.database().isOpen()) {
				return;
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void report(long processed, long total) {
		if (total > 0) {
			int percent = (int) Math.min(100, (processed * 100) / total);
			progress(QueryMessages.importStatus("AllTheLogs import: " + processed + "/" + total + " (" + percent + "%)"));
		} else {
			progress(QueryMessages.importStatus("AllTheLogs import: " + processed + " messages..."));
		}
	}

	private void save(ImportProgress progress) {
		ImportProgress stored = progress.withSilenced(silenced.get());
		latest = stored;
		database.saveImportProgress(stored);
	}

	private void saveStopped(ImportProgress progress) {
		save(progress.withStatus(ImportProgress.STATUS_STOPPED));
		chat(QueryMessages.importStatus("AllTheLogs import stopped (" + progress.processed() + " messages)."));
	}

	private void progress(Component message) {
		if (!silenced.get()) {
			chat(message);
		}
	}

	private static void chat(Component message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendSystemMessage(message);
			}
		});
	}
}
