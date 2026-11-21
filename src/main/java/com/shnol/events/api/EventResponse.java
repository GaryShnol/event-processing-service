package com.shnol.events.api;

import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
		String eventId,
		String customerId,
		EventType type,
		BigDecimal amount,
		String currency,
		EventStatus status,
		Instant timestamp,
		Instant createdAt,
		Instant processedAt,
		String failureReason) {

	static EventResponse from(Event event) {
		return new EventResponse(event.eventId(), event.customerId(), event.type(), event.amount(), event.currency(),
				event.status(), event.occurredAt(), event.createdAt(), event.processedAt(), event.failureReason());
	}
}
