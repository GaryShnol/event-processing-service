package com.shnol.events.application;

import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventProcessor;
import com.shnol.events.domain.PermanentProcessingException;
import com.shnol.events.persistence.EventStateStore;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Claims an event, runs the business rule and stores the outcome. Returning normally means the
 * message can be acknowledged: the final state is stored, or there was nothing to do. Any exception
 * other than a permanent processing error propagates so the message is redelivered.
 */
@Service
public class EventProcessingService {

	private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

	private final EventStateStore stateStore;
	private final EventProcessor processor;

	EventProcessingService(EventStateStore stateStore, EventProcessor processor) {
		this.stateStore = stateStore;
		this.processor = processor;
	}

	public void process(String eventId) {
		Optional<Event> claimed = stateStore.claim(eventId);
		if (claimed.isEmpty()) {
			log.debug("Event not claimed: finished, held by another worker or out of attempts");
			return;
		}
		Event event = claimed.get();
		log.info("Event claimed, attempt {}", event.attempts());

		try {
			processor.process(event);
		}
		catch (PermanentProcessingException e) {
			if (stateStore.fail(eventId, event.claimToken(), e.getMessage())) {
				log.info("Event failed: {}", e.getMessage());
			}
			else {
				logLostClaim();
			}
			return;
		}

		if (stateStore.complete(eventId, event.claimToken())) {
			log.info("Event processed");
		}
		else {
			logLostClaim();
		}
	}

	private static void logLostClaim() {
		log.warn("Claim lost before the result was stored: the lease expired and another worker claimed the event");
	}
}
