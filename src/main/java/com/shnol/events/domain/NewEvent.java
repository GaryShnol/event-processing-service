package com.shnol.events.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** An event as submitted by a client, before the service has stored it. */
public record NewEvent(
		String eventId,
		String customerId,
		EventType type,
		BigDecimal amount,
		String currency,
		Instant occurredAt,
		Map<String, String> metadata) {

	public NewEvent {
		// MongoDB stores dates in milliseconds; truncating here keeps the hash, the document and the response in agreement.
		occurredAt = occurredAt.truncatedTo(ChronoUnit.MILLIS);
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	/**
	 * SHA-256 over the normalised fields, so a retry that differs only in amount scale (1250.5 vs
	 * 1250.50), timestamp notation or metadata order counts as the same payload. Every value is
	 * length-prefixed so no two different payloads can produce the same input.
	 */
	public String payloadHash() {
		StringBuilder canonical = new StringBuilder();
		append(canonical, eventId);
		append(canonical, customerId);
		append(canonical, type.name());
		append(canonical, amount.stripTrailingZeros().toPlainString());
		append(canonical, currency);
		append(canonical, occurredAt.toString());
		new TreeMap<>(metadata).forEach((key, value) -> {
			append(canonical, key);
			append(canonical, value);
		});
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required on every Java platform", e);
		}
	}

	private static void append(StringBuilder canonical, String value) {
		canonical.append(value.length()).append(':').append(value).append(';');
	}
}
