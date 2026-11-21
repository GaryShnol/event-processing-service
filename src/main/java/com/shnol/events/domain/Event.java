package com.shnol.events.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Document("events")
public record Event(
		@Id String eventId,
		String customerId,
		EventType type,
		@Field(targetType = FieldType.DECIMAL128) BigDecimal amount,
		String currency,
		Instant occurredAt,
		Map<String, String> metadata,
		String payloadHash,
		EventStatus status,
		int attempts,
		String claimToken,
		Instant lockedUntil,
		Instant lastPublishedAt,
		String failureReason,
		Instant createdAt,
		Instant updatedAt,
		Instant processedAt) {

	/** A stored event that hasn't been processed yet; it counts as published at creation for the sweeper. */
	public static Event received(NewEvent newEvent, Instant now) {
		return new Event(newEvent.eventId(), newEvent.customerId(), newEvent.type(), newEvent.amount().setScale(2),
				newEvent.currency(), newEvent.occurredAt(), newEvent.metadata(), newEvent.payloadHash(),
				EventStatus.RECEIVED, 0, null, null, now, null, now, now, null);
	}
}
