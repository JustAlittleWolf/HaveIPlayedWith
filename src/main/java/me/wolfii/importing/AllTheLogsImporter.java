package me.wolfii.importing;

import me.wolfii.allthelogs.api.AllTheLogs;
import me.wolfii.allthelogs.api.ChatEntry;
import me.wolfii.allthelogs.api.ChatQuery;
import me.wolfii.allthelogs.api.LogDatabase;
import me.wolfii.command.QueryMessages;
import me.wolfii.db.ImportProgress;
import me.wolfii.db.PlayerDatabase;
import me.wolfii.net.CraftyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AllTheLogsImporter {
	private static final Logger LOGGER = LoggerFactory.getLogger("haveiplayedwith");
	private static final int PAGE_SIZE = 400;
	private static final long SAVE_EVERY = 250;
	/** Most log lines hold no username, so throttle by time rather than by lines walked. */
	private static final long REPORT_INTERVAL_NANOS = 15_000_000_000L;
	private final PlayerDatabase database;
	private final CraftyClient crafty;
	private final ImportControls controls;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-import");
		thread.setDaemon(true);
		return thread;
	});

	public AllTheLogsImporter(PlayerDatabase database, CraftyClient crafty, ImportControls controls) {
		this.database = database;
		this.crafty = crafty;
		this.controls = controls;
	}

	public void resumeIfNeeded() {
		if (!controls.trySchedule()) {
			return;
		}
		worker.execute(() -> {
			try {
				Optional<ImportProgress> progress = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS);
				progress.ifPresent(controls::save);
				if (progress.isPresent() && ImportProgress.STATUS_RUNNING.equals(progress.get().status())) {
					controls.progress(QueryMessages.importStatus("Resuming AllTheLogs import..."));
					runImport(progress.get());
				}
			} finally {
				controls.unschedule();
			}
		});
	}

	public void startFromCommand() {
		if (!controls.trySchedule()) {
			controls.chat(QueryMessages.importStatus("AllTheLogs import is already running."));
			return;
		}
		controls.clearStop();
		worker.execute(() -> {
			try {
				Optional<ImportProgress> existing = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS);
				ImportProgress start = existing
					.filter(progress -> !ImportProgress.STATUS_DONE.equals(progress.status()))
					.orElseGet(() -> new ImportProgress(
						ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportProgress.STATUS_RUNNING,
						existing.map(ImportProgress::silenced).orElse(controls.silenced())
					));
				start = start.withStatus(ImportProgress.STATUS_RUNNING);
				controls.chat(QueryMessages.importStatus("Starting AllTheLogs import in the background."));
				runImport(start);
			} finally {
				controls.unschedule();
			}
		});
	}

	private void runImport(ImportProgress start) {
		try {
			waitUntilOpen();
			if (controls.stopRequested()) {
				controls.saveStopped(start);
				return;
			}
			LogDatabase logs = AllTheLogs.database();
			if (!logs.isOpen()) {
				controls.progress(QueryMessages.importStatus("AllTheLogs is not ready yet. The import will retry next launch."));
				controls.save(start.withStatus(ImportProgress.STATUS_RUNNING));
				return;
			}
			long total = start.total() >= 0 ? start.total() : logs.countMatches(ChatQuery.all()).join();
			long processed = start.processed();
			LocalDateTime lastTimestamp = start.lastTimestamp();
			long skip = start.skip();
			long lastSave = processed;
			long lastReportAt = System.nanoTime();
			controls.save(new ImportProgress(
				ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_RUNNING, controls.silenced()
			));
			while (true) {
				if (controls.stopRequested()) {
					controls.saveStopped(controls.latest().withCursor(processed, lastTimestamp, skip));
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
					if (controls.stopRequested()) {
						controls.saveStopped(controls.latest().withCursor(processed, lastTimestamp, skip));
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
						controls.save(new ImportProgress(
							ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_RUNNING, controls.silenced()
						));
						long now = System.nanoTime();
						if (now - lastReportAt >= REPORT_INTERVAL_NANOS) {
							lastReportAt = now;
							report(processed, total);
						}
					}
				}
			}
			controls.save(new ImportProgress(
				ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_DONE, controls.silenced()
			));
			controls.chat(QueryMessages.importStatus("AllTheLogs import finished (" + processed + " messages)."));
		} catch (Exception e) {
			LOGGER.warn("AllTheLogs import failed", e);
			controls.chat(QueryMessages.importStatus("AllTheLogs import failed. It will resume next launch."));
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
			if (controls.stopRequested() || AllTheLogs.database().isOpen()) {
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
			controls.progress(QueryMessages.importStatus("AllTheLogs import: " + processed + "/" + total + " (" + percent + "%)"));
		} else {
			controls.progress(QueryMessages.importStatus("AllTheLogs import: " + processed + " messages..."));
		}
	}
}
