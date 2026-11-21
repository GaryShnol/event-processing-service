package com.shnol.events.api;

import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record CreateEventRequest(
		@NotNull @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE) String eventId,
		@NotNull @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE) String customerId,
		@NotNull EventType type,
		@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
		@NotNull @IsoCurrency String currency,
		@NotNull Instant timestamp,
		@BoundedMetadata Map<String, String> metadata) {

	static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";
	static final String ID_MESSAGE = "must be 1 to 64 letters, digits, '_' or '-'";

	NewEvent toNewEvent() {
		return new NewEvent(eventId, customerId, type, amount, currency, timestamp, metadata);
	}
}
