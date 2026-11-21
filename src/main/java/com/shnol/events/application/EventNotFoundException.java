package com.shnol.events.application;

public class EventNotFoundException extends RuntimeException {

	public EventNotFoundException(String eventId) {
		super("Event %s not found".formatted(eventId));
	}
}
