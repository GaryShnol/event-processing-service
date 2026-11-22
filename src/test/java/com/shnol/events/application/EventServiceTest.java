package com.shnol.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.persistence.EventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class EventServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-13T20:15:01Z");
	private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
	private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");

	@Mock
	private EventRepository repository;

	@Mock
	private EventPublisher publisher;

	private EventService service() {
		EventsProperties properties = new EventsProperties(new EventsProperties.Queues("events-queue"),
				Duration.ofSeconds(3), Duration.ofSeconds(60), 3, new BigDecimal("10000.00"),
				new EventsProperties.Sweeper(Duration.ofSeconds(30), Duration.ofMinutes(2), 100),
				new EventsProperties.Paging(20, 100), new EventsProperties.Metadata(20, 64, 256));
		return new EventService(repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC), properties);
	}

	@Test
	void newEventIsInsertedAsReceivedAndPublished() {
		EventStatus status = service().create(newEvent("1250.50"));

		ArgumentCaptor<Event> inserted = ArgumentCaptor.forClass(Event.class);
		verify(repository).insert(inserted.capture());
		assertThat(inserted.getValue().status()).isEqualTo(EventStatus.RECEIVED);
		assertThat(inserted.getValue().createdAt()).isEqualTo(NOW);
		assertThat(inserted.getValue().lastPublishedAt()).isEqualTo(NOW);
		verify(publisher).publish("evt-1");
		assertThat(status).isEqualTo(EventStatus.RECEIVED);
	}

	@Test
	void publishFailureAfterInsertIsLoggedAndStillReturnsReceived(CapturedOutput output) {
		doThrow(new IllegalStateException("SQS unavailable")).when(publisher).publish("evt-1");

		assertThat(service().create(newEvent("1250.50"))).isEqualTo(EventStatus.RECEIVED);

		verify(repository).insert(any());
		assertThat(output.getOut()).contains("Publish failed, the sweeper will publish the event later");
	}

	@Test
	void duplicateWithSamePayloadReturnsCurrentStatusWithoutPublishing() {
		doThrow(new DuplicateKeyException("duplicate")).when(repository).insert(any());
		when(repository.findById("evt-1")).thenReturn(Optional.of(stored("1250.5", EventStatus.PROCESSED)));

		assertThat(service().create(newEvent("1250.50"))).isEqualTo(EventStatus.PROCESSED);
		verifyNoInteractions(publisher);
	}

	@Test
	void duplicateWithDifferentPayloadIsAConflict() {
		doThrow(new DuplicateKeyException("duplicate")).when(repository).insert(any());
		when(repository.findById("evt-1")).thenReturn(Optional.of(stored("99.00", EventStatus.RECEIVED)));

		assertThatExceptionOfType(EventConflictException.class).isThrownBy(() -> service().create(newEvent("1250.50")));
		verifyNoInteractions(publisher);
	}

	@Test
	void failedInsertIsNotPublished() {
		doThrow(new DataAccessResourceFailureException("down")).when(repository).insert(any());

		assertThatExceptionOfType(DataAccessResourceFailureException.class).isThrownBy(() -> service().create(newEvent("1")));
		verify(publisher, never()).publish(any());
	}

	@Test
	void unknownEventIsNotFound() {
		when(repository.findById("evt-missing")).thenReturn(Optional.empty());

		assertThatExceptionOfType(EventNotFoundException.class).isThrownBy(() -> service().get("evt-missing"));
	}

	@Test
	void customerQueryUsesConfiguredDefaultPageSize() {
		when(repository.findByCustomer(eq("customer-1"), eq(FROM), eq(TO), eq(null), eq(PageRequest.of(2, 20))))
				.thenReturn(Page.empty());

		assertThat(service().findForCustomer("customer-1", FROM, TO, null, 2, null)).isEmpty();
	}

	@Test
	void customerQueryRejectsSizeAboveConfiguredMaximum() {
		assertThatExceptionOfType(InvalidQueryException.class)
				.isThrownBy(() -> service().findForCustomer("customer-1", null, null, null, 0, 101))
				.satisfies(e -> assertThat(e.parameter()).isEqualTo("size"));
		verifyNoInteractions(repository);
	}

	@Test
	void customerQueryRejectsFromAfterTo() {
		assertThatExceptionOfType(InvalidQueryException.class)
				.isThrownBy(() -> service().findForCustomer("customer-1", TO, FROM, null, 0, 10))
				.satisfies(e -> assertThat(e.parameter()).isEqualTo("from"));
		verifyNoInteractions(repository);
	}

	private static NewEvent newEvent(String amount) {
		return new NewEvent("evt-1", "customer-1", EventType.PAYMENT, new BigDecimal(amount), "ILS",
				Instant.parse("2026-09-13T20:15:00Z"), Map.of("source", "mobile"));
	}

	private static Event stored(String amount, EventStatus status) {
		Event received = Event.received(newEvent(amount), NOW);
		return new Event(received.eventId(), received.customerId(), received.type(), received.amount(),
				received.currency(), received.occurredAt(), received.metadata(), received.payloadHash(), status, 1, null,
				null, received.lastPublishedAt(), null, received.createdAt(), NOW, null);
	}
}
