package com.shnol.events.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewEventTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-09-13T20:15:00Z");

	@Test
	void amountScaleDoesNotChangeTheHash() {
		assertThat(event("1250.5", Map.of()).payloadHash()).isEqualTo(event("1250.50", Map.of()).payloadHash());
	}

	@Test
	void metadataOrderDoesNotChangeTheHash() {
		Map<String, String> first = new LinkedHashMap<>();
		first.put("source", "mobile");
		first.put("channel", "app");
		Map<String, String> second = new LinkedHashMap<>();
		second.put("channel", "app");
		second.put("source", "mobile");

		assertThat(event("10", first).payloadHash()).isEqualTo(event("10", second).payloadHash());
	}

	@Test
	void sameInstantWrittenWithAnOffsetHasTheSameHash() {
		Instant withOffset = OffsetDateTime.parse("2026-09-13T22:15:00+02:00").toInstant();
		NewEvent other = new NewEvent("evt-1", "customer-1", EventType.PAYMENT, new BigDecimal("10"), "ILS", withOffset, Map.of());

		assertThat(other.payloadHash()).isEqualTo(event("10", Map.of()).payloadHash());
	}

	@Test
	void timestampIsTruncatedToTheMillisecondsMongoDbStores() {
		NewEvent precise = new NewEvent("evt-1", "customer-1", EventType.PAYMENT, new BigDecimal("10"), "ILS",
				Instant.parse("2026-09-13T20:15:00.123456Z"), Map.of());
		NewEvent millis = new NewEvent("evt-1", "customer-1", EventType.PAYMENT, new BigDecimal("10"), "ILS",
				Instant.parse("2026-09-13T20:15:00.123Z"), Map.of());

		assertThat(precise.occurredAt()).isEqualTo(Instant.parse("2026-09-13T20:15:00.123Z"));
		assertThat(precise.payloadHash()).isEqualTo(millis.payloadHash());
	}

	@Test
	void differentAmountChangesTheHash() {
		assertThat(event("1250.51", Map.of()).payloadHash()).isNotEqualTo(event("1250.50", Map.of()).payloadHash());
	}

	@Test
	void valuesCannotBeShiftedBetweenMetadataKeysAndValues() {
		assertThat(event("10", Map.of("ab", "c")).payloadHash()).isNotEqualTo(event("10", Map.of("a", "bc")).payloadHash());
	}

	@Test
	void receivedEventStartsUnprocessedAndCountsAsPublishedAtCreation() {
		Instant now = Instant.parse("2026-09-13T20:15:01Z");

		Event event = Event.received(event("1250.5", null), now);

		assertThat(event.status()).isEqualTo(EventStatus.RECEIVED);
		assertThat(event.amount()).isEqualTo(new BigDecimal("1250.50"));
		assertThat(event.metadata()).isEmpty();
		assertThat(event.attempts()).isZero();
		assertThat(event.createdAt()).isEqualTo(now);
		assertThat(event.lastPublishedAt()).isEqualTo(now);
		assertThat(event.processedAt()).isNull();
	}

	private static NewEvent event(String amount, Map<String, String> metadata) {
		return new NewEvent("evt-1", "customer-1", EventType.PAYMENT, new BigDecimal(amount), "ILS", OCCURRED_AT, metadata);
	}
}
