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
	private final PlayerDatabase database;
	private final CraftyClient crafty;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "haveiplayedwith-import");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean running = new AtomicBoolean(false);

	public AllTheLogsImporter(PlayerDatabase database, CraftyClient crafty) {
		this.database = database;
		this.crafty = crafty;
	}

	public void resumeIfNeeded() {
		worker.execute(() -> {
			Optional<ImportProgress> progress = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS);
			if (progress.isPresent() && ImportProgress.STATUS_RUNNING.equals(progress.get().status())) {
				chat(QueryMessages.importStatus("Resuming AllTheLogs import..."));
				runImport(progress.get());
			}
		});
	}

	public void startFromCommand() {
		if (!running.compareAndSet(false, true)) {
			chat(QueryMessages.importStatus("AllTheLogs import is already running."));
			return;
		}
		worker.execute(() -> {
			try {
				Optional<ImportProgress> existing = database.importProgress(ImportProgress.SOURCE_ALLTHELOGS);
				ImportProgress start = existing
					.filter(progress -> !ImportProgress.STATUS_DONE.equals(progress.status()))
					.orElse(new ImportProgress(ImportProgress.SOURCE_ALLTHELOGS, 0, -1, null, 0, ImportProgress.STATUS_RUNNING));
				chat(QueryMessages.importStatus("Starting AllTheLogs import in the background."));
				runImport(start);
			} finally {
				running.set(false);
			}
		});
		if (!running.get()) {
			// startFromCommand already flipped running; the worker will clear it
		}
	}

	private void runImport(ImportProgress start) {
		running.set(true);
		try {
			waitUntilOpen();
			LogDatabase logs = AllTheLogs.database();
			if (!logs.isOpen()) {
				chat(QueryMessages.importStatus("AllTheLogs is not ready yet. The import will retry next launch."));
				database.saveImportProgress(new ImportProgress(
					start.source(), start.processed(), start.total(), start.lastTimestamp(), start.skip(), ImportProgress.STATUS_RUNNING
				));
				return;
			}
			long total = start.total() >= 0 ? start.total() : logs.countMatches(ChatQuery.all()).join();
			long processed = start.processed();
			LocalDateTime lastTimestamp = start.lastTimestamp();
			long skip = start.skip();
			long lastReport = processed;
			database.saveImportProgress(new ImportProgress(
				ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_RUNNING
			));
			while (true) {
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
					process(entry);
					processed++;
					if (lastTimestamp != null && entry.timestamp().equals(lastTimestamp)) {
						skip++;
					} else {
						lastTimestamp = entry.timestamp();
						skip = 1;
					}
					if (processed - lastReport >= 250) {
						lastReport = processed;
						report(processed, total);
						database.saveImportProgress(new ImportProgress(
							ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_RUNNING
						));
					}
				}
			}
			database.saveImportProgress(new ImportProgress(
				ImportProgress.SOURCE_ALLTHELOGS, processed, total, lastTimestamp, skip, ImportProgress.STATUS_DONE
			));
			chat(QueryMessages.importStatus("AllTheLogs import finished (" + processed + " messages)."));
		} catch (Exception e) {
			LOGGER.warn("AllTheLogs import failed", e);
			chat(QueryMessages.importStatus("AllTheLogs import failed. It will resume next launch."));
		} finally {
			running.set(false);
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
			if (AllTheLogs.database().isOpen()) {
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
			chat(QueryMessages.importStatus("AllTheLogs import: " + processed + "/" + total + " (" + percent + "%)"));
		} else {
			chat(QueryMessages.importStatus("AllTheLogs import: " + processed + " messages..."));
		}
	}

	private static void chat(Component message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player != null) {
				client.player.displayClientMessage(message, false);
			}
		});
	}
}
