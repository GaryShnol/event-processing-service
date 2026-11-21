package com.shnol.events.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shnol.events.IntegrationTest;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventStateStoreTest extends IntegrationTest {

	@Autowired
	private EventStateStore stateStore;

	@Autowired
	private EventRepository repository;

	@Test
	void receivedEventIsClaimedWithTokenLeaseAndOneAttempt() {
		Event event = insert(EventStatus.RECEIVED, 0, null, null);
		Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		Event claimed = stateStore.claim(event.eventId()).orElseThrow();

		assertThat(claimed.status()).isEqualTo(EventStatus.PROCESSING);
		assertThat(claimed.attempts()).isEqualTo(1);
		assertThat(claimed.claimToken()).isNotBlank();
		assertThat(claimed.lockedUntil()).isBetween(before.plusSeconds(60), Instant.now().plusSeconds(60));
		assertThat(claimed.updatedAt()).isAfterOrEqualTo(before);
		assertThat(repository.findById(event.eventId())).contains(claimed);
	}

	@Test
	void processedEventCannotBeClaimed() {
		Event event = insert(EventStatus.PROCESSED, 1, "token", Instant.now().minusSeconds(600));

		assertThat(stateStore.claim(event.eventId())).isEmpty();
		assertThat(repository.findById(event.eventId())).contains(event);
	}

	@Test
	void failedEventCannotBeClaimed() {
		Event event = insert(EventStatus.FAILED, 1, "token", Instant.now().minusSeconds(600));

		assertThat(stateStore.claim(event.eventId())).isEmpty();
	}

	@Test
	void processingEventWithLiveLeaseCannotBeClaimed() {
		Event event = insert(EventStatus.PROCESSING, 1, "token", Instant.now().plusSeconds(30));

		assertThat(stateStore.claim(event.eventId())).isEmpty();
		assertThat(repository.findById(event.eventId())).contains(event);
	}

	@Test
	void processingEventWithExpiredLeaseIsClaimedWithANewToken() {
		Event event = insert(EventStatus.PROCESSING, 1, "old-token", Instant.now().minusSeconds(1));

		Event claimed = stateStore.claim(event.eventId()).orElseThrow();

		assertThat(claimed.status()).isEqualTo(EventStatus.PROCESSING);
		assertThat(claimed.attempts()).isEqualTo(2);
		assertThat(claimed.claimToken()).isNotEqualTo("old-token");
		assertThat(claimed.lockedUntil()).isAfter(Instant.now());
	}

	@Test
	void eventOutOfAttemptsCannotBeClaimedEvenWithExpiredLease() {
		Event event = insert(EventStatus.PROCESSING, 3, "token", Instant.now().minusSeconds(1));

		assertThat(stateStore.claim(event.eventId())).isEmpty();
		assertThat(repository.findById(event.eventId())).contains(event);
	}

	@Test
	void unknownEventCannotBeClaimed() {
		assertThat(stateStore.claim("evt-missing-" + UUID.randomUUID())).isEmpty();
	}

	@Test
	void concurrentClaimsOnOneEventHaveExactlyOneWinner() throws Exception {
		Event event = insert(EventStatus.RECEIVED, 0, null, null);
		int workers = 10;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Optional<Event>>> claims = new ArrayList<>();

		try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
			for (int i = 0; i < workers; i++) {
				claims.add(executor.submit(() -> {
					start.await();
					return stateStore.claim(event.eventId());
				}));
			}
			start.countDown();
			int winners = 0;
			for (Future<Optional<Event>> claim : claims) {
				winners += claim.get().isPresent() ? 1 : 0;
			}
			assertThat(winners).isEqualTo(1);
		}
		assertThat(repository.findById(event.eventId()).orElseThrow().attempts()).isEqualTo(1);
	}

	@Test
	void completeWithCurrentTokenSetsProcessedAndProcessedAt() {
		Event claimed = stateStore.claim(insert(EventStatus.RECEIVED, 0, null, null).eventId()).orElseThrow();

		assertThat(stateStore.complete(claimed.eventId(), claimed.claimToken())).isTrue();

		Event stored = repository.findById(claimed.eventId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(EventStatus.PROCESSED);
		assertThat(stored.processedAt()).isNotNull();
		assertThat(stored.updatedAt()).isEqualTo(stored.processedAt());
		assertThat(stored.failureReason()).isNull();
	}

	@Test
	void completeWithStaleTokenMatchesNothing() {
		Event event = insert(EventStatus.PROCESSING, 1, "stale-token", Instant.now().minusSeconds(1));
		Event reclaimed = stateStore.claim(event.eventId()).orElseThrow();

		assertThat(stateStore.complete(event.eventId(), "stale-token")).isFalse();

		assertThat(repository.findById(event.eventId())).contains(reclaimed);
	}

	@Test
	void completeOfAFinishedEventMatchesNothing() {
		Event event = insert(EventStatus.FAILED, 1, "token", Instant.now().minusSeconds(1));

		assertThat(stateStore.complete(event.eventId(), "token")).isFalse();
		assertThat(repository.findById(event.eventId())).contains(event);
	}

	@Test
	void failWithCurrentTokenSetsFailedAndReason() {
		Event claimed = stateStore.claim(insert(EventStatus.RECEIVED, 0, null, null).eventId()).orElseThrow();

		assertThat(stateStore.fail(claimed.eventId(), claimed.claimToken(), "amount too large")).isTrue();

		Event stored = repository.findById(claimed.eventId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(EventStatus.FAILED);
		assertThat(stored.failureReason()).isEqualTo("amount too large");
		assertThat(stored.processedAt()).isNull();
	}

	@Test
	void failWithStaleTokenMatchesNothing() {
		Event event = insert(EventStatus.PROCESSING, 1, "stale-token", Instant.now().minusSeconds(1));
		Event reclaimed = stateStore.claim(event.eventId()).orElseThrow();

		assertThat(stateStore.fail(event.eventId(), "stale-token", "too late")).isFalse();

		assertThat(repository.findById(event.eventId())).contains(reclaimed);
	}

	private Event insert(EventStatus status, int attempts, String claimToken, Instant lockedUntil) {
		Instant createdAt = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MILLIS);
		NewEvent newEvent = new NewEvent("evt-" + UUID.randomUUID(), "customer-1", EventType.PAYMENT,
				new BigDecimal("10.00"), "ILS", createdAt, Map.of());
		Event received = Event.received(newEvent, createdAt);
		Event event = new Event(received.eventId(), received.customerId(), received.type(), received.amount(),
				received.currency(), received.occurredAt(), received.metadata(), received.payloadHash(), status,
				attempts, claimToken, lockedUntil == null ? null : lockedUntil.truncatedTo(ChronoUnit.MILLIS),
				received.lastPublishedAt(), null, createdAt, createdAt, null);
		repository.insert(event);
		return event;
	}
}
