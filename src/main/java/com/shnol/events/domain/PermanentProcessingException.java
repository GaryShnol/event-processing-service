package com.shnol.events.domain;

/** A processing failure that retrying can't fix. The message becomes the event's failureReason. */
public class PermanentProcessingException extends RuntimeException {

	public PermanentProcessingException(String message) {
		super(message);
	}
}
