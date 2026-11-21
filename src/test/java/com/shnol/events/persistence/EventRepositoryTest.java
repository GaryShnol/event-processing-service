package com.shnol.events.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.shnol.events.IntegrationTest;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class EventRepositoryTest extends IntegrationTest {

	private static final Instant DAY_1 = Instant.parse("2026-09-01T10:00:00Z");
	private static final Instant DAY_2 = Instant.parse("2026-09-02T10:00:00Z");
	private static final Instant DAY_3 = Instant.parse("2026-09-03T10:00:00Z");

	@Autowired
	private EventRepository repository;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Test
	void storesAmountAsDecimal128AndReadsItBack() {
		Event event = event(customer(), DAY_1, "1250.5");
		repository.insert(event);

		Document stored = mongoTemplate.getCollection("events").find(new Document("_id", event.eventId())).first();
		assertThat(stored.get("amount")).isEqualTo(Decimal128.parse("1250.50"));
		assertThat(stored.get("occurredAt")).isEqualTo(Date.from(DAY_1));
		assertThat(stored.getString("status")).isEqualTo("RECEIVED");
		assertThat(repository.findById(event.eventId())).get().isEqualTo(event);
	}

	@Test
	void secondInsertWithSameEventIdThrowsDuplicateKey() {
		Event event = event(customer(), DAY_1, "10");
		repository.insert(event);

		assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() -> repository.insert(event));
	}

	@Test
	void unknownEventIdIsEmpty() {
		assertThat(repository.findById("evt-missing-" + UUID.randomUUID())).isEmpty();
	}

	@Test
	void customerQueryFiltersInclusiveDateRangeAndPagesNewestFirst() {
		String customerId = customer();
		Event first = event(customerId, DAY_1, "1");
		Event second = event(customerId, DAY_2, "2");
		Event third = event(customerId, DAY_3, "3");
		List.of(first, second, third, event(customer(), DAY_2, "4")).forEach(repository::insert);

		Page<Event> page0 = repository.findByCustomer(customerId, DAY_1, DAY_3, null, PageRequest.of(0, 2));
		Page<Event> page1 = repository.findByCustomer(customerId, DAY_1, DAY_3, null, PageRequest.of(1, 2));
		Page<Event> fromDay2 = repository.findByCustomer(customerId, DAY_2, null, null, PageRequest.of(0, 10));
		Page<Event> untilDay2 = repository.findByCustomer(customerId, null, DAY_2, null, PageRequest.of(0, 10));

		assertThat(page0.getContent()).extracting(Event::eventId).containsExactly(third.eventId(), second.eventId());
		assertThat(page1.getContent()).extracting(Event::eventId).containsExactly(first.eventId());
		assertThat(page0.getTotalElements()).isEqualTo(3);
		assertThat(fromDay2.getContent()).extracting(Event::eventId).containsExactly(third.eventId(), second.eventId());
		assertThat(untilDay2.getContent()).extracting(Event::eventId).containsExactly(second.eventId(), first.eventId());
	}

	@Test
	void customerQueryOrdersEventsAtTheSameInstantByEventIdDescending() {
		String customerId = customer();
		Event a = event(customerId, DAY_1, "1", "evt-a-" + UUID.randomUUID());
		Event b = event(customerId, DAY_1, "1", "evt-b-" + UUID.randomUUID());
		repository.insert(a);
		repository.insert(b);

		assertThat(repository.findByCustomer(customerId, null, null, null, PageRequest.of(0, 10)).getContent())
				.extracting(Event::eventId).containsExactly(b.eventId(), a.eventId());
	}

	@Test
	void customerQueryFiltersByStatus() {
		String customerId = customer();
		Event received = event(customerId, DAY_1, "1");
		repository.insert(received);
		Event p = event(customerId, DAY_2, "2");
		Event processed = new Event(p.eventId(), p.customerId(), p.type(), p.amount(), p.currency(), p.occurredAt(),
				p.metadata(), p.payloadHash(), EventStatus.PROCESSED, 1, null, null, p.lastPublishedAt(), null,
				p.createdAt(), p.createdAt(), p.createdAt());
		repository.insert(processed);

		assertThat(repository.findByCustomer(customerId, null, null, EventStatus.RECEIVED, PageRequest.of(0, 10)).getContent())
				.extracting(Event::eventId).containsExactly(received.eventId());
		assertThat(repository.findByCustomer(customerId, null, null, EventStatus.PROCESSED, PageRequest.of(0, 10)).getContent())
				.extracting(Event::eventId).containsExactly(processed.eventId());
	}

	@Test
	void customerQueryWithoutStatusUsesCustomerIndexWithoutInMemorySort() {
		String plan = explain(EventRepository.customerQuery("customer-1", DAY_1, DAY_3, null));

		assertThat(plan).contains("\"indexName\": \"" + IndexInitializer.CUSTOMER_INDEX + "\"").doesNotContain("\"SORT\"");
	}

	@Test
	void customerQueryWithStatusUsesCustomerIndexWithoutInMemorySort() {
		String plan = explain(EventRepository.customerQuery("customer-1", DAY_1, DAY_3, EventStatus.PROCESSED));

		assertThat(plan).contains("\"indexName\": \"" + IndexInitializer.CUSTOMER_INDEX + "\"").doesNotContain("\"SORT\"");
	}

	private String explain(Query query) {
		Document sort = new Document("occurredAt", -1).append("_id", -1);
		Document plan = mongoTemplate.getCollection("events").find(query.getQueryObject()).sort(sort).limit(20).explain();
		return plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
	}

	private static String customer() {
		return "customer-" + UUID.randomUUID();
	}

	private static Event event(String customerId, Instant occurredAt, String amount) {
		return event(customerId, occurredAt, amount, "evt-" + UUID.randomUUID());
	}

	private static Event event(String customerId, Instant occurredAt, String amount, String eventId) {
		NewEvent newEvent = new NewEvent(eventId, customerId, EventType.PAYMENT, new BigDecimal(amount), "ILS",
				occurredAt, Map.of("source", "test"));
		return Event.received(newEvent, Instant.parse("2026-09-04T00:00:00Z"));
	}
}
