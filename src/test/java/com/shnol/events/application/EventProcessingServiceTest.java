package com.shnol.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventProcessor;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.domain.PermanentProcessingException;
import com.shnol.events.persistence.EventStateStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class EventProcessingServiceTest {

	private static final String EVENT_ID = "evt-1";
	private static final String TOKEN = "token-1";

	@Mock
	private EventStateStore stateStore;

	@Mock
	private EventProcessor processor;

	private EventProcessingService service() {
		return new EventProcessingService(stateStore, processor);
	}

	@Test
	void eventThatCannotBeClaimedIsNotProcessed() {
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.empty());

		service().process(EVENT_ID);

		verifyNoInteractions(processor);
		verify(stateStore, never()).complete(anyString(), anyString());
		verify(stateStore, never()).fail(anyString(), anyString(), anyString());
	}

	@Test
	void claimedEventIsProcessedAndCompletedWithItsClaimToken() {
		Event claimed = claimed();
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.of(claimed));
		when(stateStore.complete(EVENT_ID, TOKEN)).thenReturn(true);

		service().process(EVENT_ID);

		verify(processor).process(claimed);
		verify(stateStore).complete(EVENT_ID, TOKEN);
		verify(stateStore, never()).fail(anyString(), anyString(), anyString());
	}

	@Test
	void permanentErrorFailsTheEventWithTheReasonAndReturnsNormally() {
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.of(claimed()));
		doThrow(new PermanentProcessingException("amount too large")).when(processor).process(any());
		when(stateStore.fail(EVENT_ID, TOKEN, "amount too large")).thenReturn(true);

		assertThatCode(() -> service().process(EVENT_ID)).doesNotThrowAnyException();

		verify(stateStore).fail(EVENT_ID, TOKEN, "amount too large");
		verify(stateStore, never()).complete(anyString(), anyString());
	}

	@Test
	void lostClaimOnCompleteIsLoggedAsWarningAndReturnsNormally(CapturedOutput output) {
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.of(claimed()));
		when(stateStore.complete(EVENT_ID, TOKEN)).thenReturn(false);

		assertThatCode(() -> service().process(EVENT_ID)).doesNotThrowAnyException();

		assertThat(output).containsPattern("WARN.*Claim lost before the result was stored");
	}

	@Test
	void lostClaimOnFailIsLoggedAsWarningAndReturnsNormally(CapturedOutput output) {
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.of(claimed()));
		doThrow(new PermanentProcessingException("amount too large")).when(processor).process(any());
		when(stateStore.fail(EVENT_ID, TOKEN, "amount too large")).thenReturn(false);

		assertThatCode(() -> service().process(EVENT_ID)).doesNotThrowAnyException();

		assertThat(output).containsPattern("WARN.*Claim lost before the result was stored");
	}

	@Test
	void otherProcessingErrorPropagatesWithoutStoringAResult() {
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.of(claimed()));
		doThrow(new IllegalStateException("transient")).when(processor).process(any());

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> service().process(EVENT_ID));

		verify(stateStore, never()).complete(anyString(), anyString());
		verify(stateStore, never()).fail(anyString(), anyString(), anyString());
	}

	@Test
	void databaseErrorWhileCompletingPropagates() {
		when(stateStore.claim(EVENT_ID)).thenReturn(Optional.of(claimed()));
		when(stateStore.complete(EVENT_ID, TOKEN)).thenThrow(new DataAccessResourceFailureException("down"));

		assertThatExceptionOfType(DataAccessResourceFailureException.class).isThrownBy(() -> service().process(EVENT_ID));
	}

	private static Event claimed() {
		Instant now = Instant.parse("2026-09-13T20:15:01Z");
		Event received = Event.received(new NewEvent(EVENT_ID, "customer-1", EventType.PAYMENT, new BigDecimal("10"),
				"ILS", Instant.parse("2026-09-13T20:15:00Z"), Map.of()), now);
		return new Event(received.eventId(), received.customerId(), received.type(), received.amount(),
				received.currency(), received.occurredAt(), received.metadata(), received.payloadHash(),
				EventStatus.PROCESSING, 1, TOKEN, now.plusSeconds(60), received.lastPublishedAt(), null,
				received.createdAt(), now, null);
	}
}
