package com.shnol.events.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventProcessorTest {

	private final EventProcessor processor = new EventProcessor(new BigDecimal("10000.00"));

	@Test
	void amountAtTheMaximumSucceeds() {
		assertThatCode(() -> processor.process(event("10000"))).doesNotThrowAnyException();
	}

	@Test
	void amountAboveTheMaximumFailsPermanentlyWithTheReason() {
		assertThatExceptionOfType(PermanentProcessingException.class)
				.isThrownBy(() -> processor.process(event("10000.01")))
				.withMessage("amount 10000.01 exceeds the maximum of 10000.00");
	}

	private static Event event(String amount) {
		return Event.received(new NewEvent("evt-1", "customer-1", EventType.PAYMENT, new BigDecimal(amount), "ILS",
				Instant.parse("2026-09-13T20:15:00Z"), Map.of()), Instant.parse("2026-09-13T20:15:01Z"));
	}
}
