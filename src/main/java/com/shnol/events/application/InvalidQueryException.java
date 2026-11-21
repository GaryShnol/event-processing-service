package com.shnol.events.application;

/** A query parameter that breaks a rule depending on configuration or on another parameter. */
public class InvalidQueryException extends RuntimeException {

	private final String parameter;

	public InvalidQueryException(String parameter, String message) {
		super(message);
		this.parameter = parameter;
	}

	public String parameter() {
		return parameter;
	}
}
