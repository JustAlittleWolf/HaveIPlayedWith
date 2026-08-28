package me.wolfii.allthelogs.api;

import java.time.LocalDateTime;

public interface ChatQuery {
	static ChatQuery all() {
		throw new AssertionError("AllTheLogs stub");
	}

	ChatQuery withLimit(long limit);

	ChatQuery withSkip(long skip);

	ChatQuery startingAt(LocalDateTime startingAt);

	ChatQuery withSort(Sort sort);

	enum Sort {
		ASCENDING,
		DESCENDING
	}
}
