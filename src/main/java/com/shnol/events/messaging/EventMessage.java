package com.shnol.events.messaging;

/** The SQS message body: {"eventId": "..."}. */
record EventMessage(String eventId) {

	// Failing the conversion leaves the message unacknowledged, so the redrive policy moves it to the DLQ
	// instead of it being acknowledged without any work done.
	EventMessage {
		if (eventId == null || eventId.isBlank()) {
			throw new IllegalArgumentException("message has no eventId");
		}
	}
}
