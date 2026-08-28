package me.wolfii.allthelogs.api;

import java.time.LocalDateTime;

public interface ChatEntry {
	ChatLog chatLog();

	LocalDateTime timestamp();

	String message();
}
