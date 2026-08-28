package me.wolfii.allthelogs.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface ChatLog {
	LogSource source();

	LocalDate date();

	LocalDateTime startTime();
}
