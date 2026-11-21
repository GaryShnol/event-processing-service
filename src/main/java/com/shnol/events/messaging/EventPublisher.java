package com.shnol.events.messaging;

/** Sends the "process event X" signal. The queue carries only the eventId; MongoDB holds the state. */
public interface EventPublisher {

	void publish(String eventId);
}
