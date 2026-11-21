package com.shnol.events.recovery;

import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.messaging.EventPublisher;
import com.shnol.events.persistence.EventRepository;
import com.shnol.events.persistence.EventStateStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails events that ran out of attempts and re-publishes events whose message was never sent or got lost.
 * Several instances may sweep at once: a duplicate publish is harmless because the claim is atomic.
 * A publish failure ends the run; the next run picks up where it stopped.
 */
@Component
@ConditionalOnBooleanProperty(name = "events.sweeper.enabled", matchIfMissing = true)
class StuckEventSweeper {

	private static final Logger log = LoggerFactory.getLogger(StuckEventSweeper.class);

	private final EventStateStore stateStore;
	private final EventRepository repository;
	private final EventPublisher publisher;
	private final Clock clock;
	private final EventsProperties.Sweeper settings;

	StuckEventSweeper(EventStateStore stateStore, EventRepository repository, EventPublisher publisher, Clock clock,
			EventsProperties properties) {
		this.stateStore = stateStore;
		this.repository = repository;
		this.publisher = publisher;
		this.clock = clock;
		this.settings = properties.sweeper();
	}

	@Scheduled(fixedDelayString = "${events.sweeper.interval}")
	void sweep() {
		long failed = stateStore.failExhausted();

		Instant now = clock.instant();
		List<Event> stuck = repository.findStuck(now, now.minus(settings.republishAfter()), settings.batchSize());
		for (Event event : stuck) {
			try (var ignored = MDC.putCloseable("eventId", event.eventId())) {
				publisher.publish(event.eventId());
				stateStore.markRepublished(event.eventId(), event.lastPublishedAt());
				log.info("Event re-published, status {}", event.status());
			}
		}
		log.info("Sweep finished: {} failed after max attempts, {} re-published", failed, stuck.size());
	}
}
