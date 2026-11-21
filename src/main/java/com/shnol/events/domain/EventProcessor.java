package com.shnol.events.domain;

import java.math.BigDecimal;

/** The simulated business rule. It has no side effects; the caller stores the outcome. */
public class EventProcessor {

	private final BigDecimal maxAmount;

	public EventProcessor(BigDecimal maxAmount) {
		this.maxAmount = maxAmount;
	}

	/** Throws {@link PermanentProcessingException} when the amount is above the maximum. */
	public void process(Event event) {
		if (event.amount().compareTo(maxAmount) > 0) {
			throw new PermanentProcessingException("amount %s exceeds the maximum of %s"
					.formatted(event.amount().toPlainString(), maxAmount.toPlainString()));
		}
	}
}
