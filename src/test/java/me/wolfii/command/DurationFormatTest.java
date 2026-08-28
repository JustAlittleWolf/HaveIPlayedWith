package me.wolfii.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatTest {
	@Test
	void minutesBelowOneHour() {
		assertEquals("1 minute", DurationFormat.compact(1));
		assertEquals("59 minutes", DurationFormat.compact(59));
	}

	@Test
	void hoursUntilNinetyNine() {
		assertEquals("1.0 hours", DurationFormat.compact(60));
		assertEquals("99.0 hours", DurationFormat.compact(99 * 60));
	}

	@Test
	void daysAfterNinetyNineHours() {
		assertEquals("4.2 days", DurationFormat.compact(100 * 60));
	}

	@Test
	void hoverOmitsDaysUnderTwentyFourHours() {
		assertEquals("5:03", DurationFormat.hover(5 * 60 + 3));
		assertEquals("1 day, 00:00", DurationFormat.hover(24 * 60));
		assertEquals("2 days, 03:15", DurationFormat.hover(2 * 24 * 60 + 3 * 60 + 15));
	}
}
