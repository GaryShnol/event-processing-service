package com.shnol.events.application;

import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.messaging.EventPublisher;
import com.shnol.events.persistence.EventRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class EventService {

	private static final Logger log = LoggerFactory.getLogger(EventService.class);

	private final EventRepository repository;
	private final EventPublisher publisher;
	private final Clock clock;
	private final EventsProperties properties;

	EventService(EventRepository repository, EventPublisher publisher, Clock clock, EventsProperties properties) {
		this.repository = repository;
		this.publisher = publisher;
		this.clock = clock;
		this.properties = properties;
	}

	/**
	 * Stores a new event and publishes it, or returns the current status of an identical earlier one.
	 * The unique _id decides between concurrent duplicates, so there is no lookup before the insert.
	 */
	public EventStatus create(NewEvent newEvent) {
		Event event = Event.received(newEvent, clock.instant());
		try (var ignored = MDC.putCloseable("eventId", event.eventId())) {
			try {
				repository.insert(event);
			}
			catch (DuplicateKeyException e) {
				return existingStatus(event);
			}
			log.info("Event received");
			publisher.publish(event.eventId());
			return event.status();
		}
	}

	public Event get(String eventId) {
		return repository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
	}

	public Page<Event> findForCustomer(String customerId, Instant from, Instant to, EventStatus status, int page, Integer size) {
		int maxSize = properties.paging().maxSize();
		if (size != null && size > maxSize) {
			throw new InvalidQueryException("size", "must be at most " + maxSize);
		}
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidQueryException("from", "must not be after to");
		}
		int pageSize = size == null ? properties.paging().defaultSize() : size;
		return repository.findByCustomer(customerId, from, to, status, PageRequest.of(page, pageSize));
	}

	private EventStatus existingStatus(Event event) {
		// Events are never deleted, so the document that caused the duplicate key is still there.
		Event existing = repository.findById(event.eventId()).orElseThrow();
		if (!existing.payloadHash().equals(event.payloadHash())) {
			log.info("Event rejected: eventId reused with a different payload");
			throw new EventConflictException(event.eventId());
		}
		log.info("Duplicate event, returning status {}", existing.status());
		return existing.status();
	}
}
