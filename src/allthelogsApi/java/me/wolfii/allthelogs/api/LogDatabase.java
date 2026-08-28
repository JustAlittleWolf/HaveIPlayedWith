package me.wolfii.allthelogs.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class LogDatabase {
	public boolean isOpen() {
		return false;
	}

	public CompletableFuture<List<ChatEntry>> findEntries(ChatQuery query) {
		return CompletableFuture.failedFuture(new IllegalStateException("AllTheLogs stub"));
	}

	public CompletableFuture<Long> countMatches(ChatQuery query) {
		return CompletableFuture.failedFuture(new IllegalStateException("AllTheLogs stub"));
	}
}
