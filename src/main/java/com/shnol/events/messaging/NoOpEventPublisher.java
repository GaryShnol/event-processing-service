package com.shnol.events.messaging;

import org.springframework.stereotype.Component;

// Placeholder until the SQS publisher exists; the event stays RECEIVED.
@Component
class NoOpEventPublisher implements EventPublisher {

	@Override
	public void publish(String eventId) {
	}
}
