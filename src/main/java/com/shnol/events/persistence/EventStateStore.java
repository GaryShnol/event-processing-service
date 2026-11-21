package com.shnol.events.persistence;

import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Processing state transitions, each one a single conditional update so concurrent workers can't both win. */
@Repository
public class EventStateStore {

	public static final String MAX_ATTEMPTS_EXCEEDED = "max attempts exceeded";

	private final MongoTemplate mongoTemplate;
	private final Clock clock;
	private final EventsProperties properties;

	EventStateStore(MongoTemplate mongoTemplate, Clock clock, EventsProperties properties) {
		this.mongoTemplate = mongoTemplate;
		this.clock = clock;
		this.properties = properties;
	}

	/**
	 * Claims the event with a new token and lease if it is RECEIVED, or PROCESSING with an expired lease,
	 * and has attempts left. Empty when it is finished, held by another worker or out of attempts.
	 */
	public Optional<Event> claim(String eventId) {
		Instant now = clock.instant();
		Query query = Query.query(Criteria.where("_id").is(eventId)
				.and("attempts").lt(properties.maxAttempts())
				.orOperator(
						Criteria.where("status").is(EventStatus.RECEIVED),
						Criteria.where("status").is(EventStatus.PROCESSING).and("lockedUntil").lt(now)));
		Update update = new Update()
				.set("status", EventStatus.PROCESSING)
				.set("claimToken", UUID.randomUUID().toString())
				.set("lockedUntil", now.plus(properties.lease()))
				.set("updatedAt", now)
				.inc("attempts", 1);
		return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
				FindAndModifyOptions.options().returnNew(true), Event.class));
	}

	/** False when the claim was lost: the event is no longer PROCESSING under this token. */
	public boolean complete(String eventId, String claimToken) {
		Instant now = clock.instant();
		return finish(eventId, claimToken, new Update()
				.set("status", EventStatus.PROCESSED)
				.set("processedAt", now)
				.set("updatedAt", now));
	}

	/** False when the claim was lost, as for {@link #complete}. */
	public boolean fail(String eventId, String claimToken, String reason) {
		return finish(eventId, claimToken, new Update()
				.set("status", EventStatus.FAILED)
				.set("failureReason", reason)
				.set("updatedAt", clock.instant()));
	}

	/**
	 * Marks every PROCESSING event whose lease has expired and that has no attempts left as FAILED, in one
	 * update. A lease that is still held is never matched, so a worker on its last attempt can finish.
	 */
	public long failExhausted() {
		Instant now = clock.instant();
		return mongoTemplate.updateMulti(exhaustedQuery(now, properties.maxAttempts()), new Update()
				.set("status", EventStatus.FAILED)
				.set("failureReason", MAX_ATTEMPTS_EXCEEDED)
				.set("updatedAt", now), Event.class).getModifiedCount();
	}

	static Query exhaustedQuery(Instant now, int maxAttempts) {
		return Query.query(Criteria.where("status").is(EventStatus.PROCESSING)
				.and("lockedUntil").lt(now)
				.and("attempts").gte(maxAttempts));
	}

	/**
	 * Records a re-publish, unless the event has finished or another sweeper has recorded one since
	 * previousPublishedAt was read.
	 */
	public void markRepublished(String eventId, Instant previousPublishedAt) {
		Query query = Query.query(Criteria.where("_id").is(eventId)
				.and("status").in(EventStatus.RECEIVED, EventStatus.PROCESSING)
				.and("lastPublishedAt").is(previousPublishedAt));
		mongoTemplate.updateFirst(query, new Update().set("lastPublishedAt", clock.instant()), Event.class);
	}

	private boolean finish(String eventId, String claimToken, Update update) {
		Query query = Query.query(Criteria.where("_id").is(eventId)
				.and("status").is(EventStatus.PROCESSING)
				.and("claimToken").is(claimToken));
		return mongoTemplate.updateFirst(query, update, Event.class).getMatchedCount() == 1;
	}
}
