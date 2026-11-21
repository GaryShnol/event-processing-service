package com.shnol.events.application;

public class EventConflictException extends RuntimeException {

	public EventConflictException(String eventId) {
		super("Event %s already exists with a different payload".formatted(eventId));
	}
}
