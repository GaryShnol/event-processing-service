package com.shnol.events.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shnol.events.application.EventPublisher;
import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.persistence.EventRepository;
import com.shnol.events.persistence.EventStateStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class StuckEventSweeperTest {

	private static final Instant NOW = Instant.parse("2026-09-13T20:15:00Z");

	@Mock
	private EventStateStore stateStore;

	@Mock
	private EventRepository repository;

	@Mock
	private EventPublisher publisher;

	private StuckEventSweeper sweeper() {
		EventsProperties properties = new EventsProperties(new EventsProperties.Queues("events-queue"),
				Duration.ofSeconds(3), Duration.ofSeconds(60), 3, new BigDecimal("10000.00"),
				new EventsProperties.Sweeper(Duration.ofSeconds(30), Duration.ofMinutes(2), 50),
				new EventsProperties.Paging(20, 100, 10000), new EventsProperties.Metadata(20, 64, 256));
		return new StuckEventSweeper(stateStore, repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC), properties);
	}

	@Test
	void failsExhaustedEventsThenRepublishesABatchPublishedBeforeTheCutoff(CapturedOutput output) {
		Event first = event("evt-1", NOW.minusSeconds(600));
		Event second = event("evt-2", NOW.minusSeconds(300));
		when(stateStore.failExhausted()).thenReturn(4L);
		when(repository.findStuck(NOW, NOW.minus(Duration.ofMinutes(2)), 50)).thenReturn(List.of(first, second));

		sweeper().sweep();

		InOrder order = inOrder(stateStore, repository, publisher);
		order.verify(stateStore).failExhausted();
		order.verify(repository).findStuck(NOW, NOW.minus(Duration.ofMinutes(2)), 50);
		order.verify(publisher).publish("evt-1");
		order.verify(stateStore).markRepublished("evt-1", first.lastPublishedAt());
		order.verify(publisher).publish("evt-2");
		order.verify(stateStore).markRepublished("evt-2", second.lastPublishedAt());
		assertThat(output.getOut())
				.contains("Sweep finished: 4 failed after max attempts, 2 re-published");
	}

	@Test
	void publishFailureEndsTheRunWithoutRecordingThatEventAsPublished() {
		Event event = event("evt-1", NOW.minusSeconds(600));
		when(repository.findStuck(any(), any(), anyInt())).thenReturn(List.of(event, event("evt-2", NOW)));
		doThrow(new IllegalStateException("SQS unavailable")).when(publisher).publish("evt-1");

		assertThatIllegalStateException().isThrownBy(() -> sweeper().sweep());

		verify(stateStore, never()).markRepublished(any(), any());
		verify(publisher, never()).publish("evt-2");
	}

	private static Event event(String eventId, Instant lastPublishedAt) {
		return Event.received(new NewEvent(eventId, "customer-1", EventType.PAYMENT, new BigDecimal("10.00"), "ILS",
				NOW, Map.of()), lastPublishedAt);
	}
}
