package com.shnol.events.persistence;

import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

	static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("_id"));

	private final MongoTemplate mongoTemplate;

	EventRepository(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	/** Throws {@link org.springframework.dao.DuplicateKeyException} if the eventId already exists. */
	public void insert(Event event) {
		mongoTemplate.insert(event);
	}

	public Optional<Event> findById(String eventId) {
		return Optional.ofNullable(mongoTemplate.findById(eventId, Event.class));
	}

	/** Newest first by occurredAt, then _id; served by the customer index without an in-memory sort. */
	public Page<Event> findByCustomer(String customerId, Instant from, Instant to, EventStatus status, Pageable pageable) {
		Query query = customerQuery(customerId, from, to, status);
		List<Event> content = mongoTemplate.find(pagedQuery(query, pageable), Event.class);
		return PageableExecutionUtils.getPage(content, pageable, () -> mongoTemplate.count(query, Event.class));
	}

	static Query pagedQuery(Query query, Pageable pageable) {
		return Query.of(query).with(pageable).with(NEWEST_FIRST);
	}

	static Query customerQuery(String customerId, Instant from, Instant to, EventStatus status) {
		Criteria criteria = Criteria.where("customerId").is(customerId);
		if (from != null || to != null) {
			Criteria occurredAt = criteria.and("occurredAt");
			if (from != null) {
				occurredAt.gte(from);
			}
			if (to != null) {
				occurredAt.lte(to);
			}
		}
		if (status != null) {
			criteria.and("status").is(status);
		}
		return Query.query(criteria);
	}
}
