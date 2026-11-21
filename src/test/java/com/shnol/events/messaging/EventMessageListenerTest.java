package com.shnol.events.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shnol.events.IntegrationTest;
import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventProcessor;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.persistence.EventRepository;
import com.shnol.events.persistence.EventStateStore;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@ExtendWith(OutputCaptureExtension.class)
class EventMessageListenerTest extends IntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	@Autowired
	private RestTestClient client;

	@Autowired
	private SqsTemplate sqsTemplate;

	@Autowired
	private SqsAsyncClient sqsClient;

	@Autowired
	private EventRepository repository;

	@Autowired
	private EventsProperties properties;

	@MockitoSpyBean
	private EventProcessor processor;

	@MockitoSpyBean
	private EventStateStore stateStore;

	@Test
	void postedEventIsEventuallyProcessedAndEachTransitionIsLoggedWithEventId(CapturedOutput output) {
		String eventId = eventId();
		post(eventId, "1250.50");

		await().atMost(TIMEOUT).untilAsserted(() -> client.get().uri("/api/events/{id}", eventId).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("PROCESSED")
				.jsonPath("$.processedAt").isNotEmpty()
				.jsonPath("$.failureReason").doesNotExist());

		String id = "\"eventId\":\"%s\"".formatted(eventId);
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(output.getOut())
				.containsPattern("\"message\":\"Event received\"," + id)
				.containsPattern("\"message\":\"Event claimed, attempt 1\"," + id)
				.containsPattern("\"message\":\"Event processed\"," + id));
	}

	@Test
	void amountAboveTheMaximumEndsFailedWithAReasonAndLeavesTheQueueEmpty() {
		String eventId = eventId();
		post(eventId, "10000.01");

		await().atMost(TIMEOUT).untilAsserted(() -> client.get().uri("/api/events/{id}", eventId).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("FAILED")
				.jsonPath("$.failureReason").isEqualTo("amount 10000.01 exceeds the maximum of 10000.00")
				.jsonPath("$.processedAt").doesNotExist());
		awaitQueueEmpty();
	}

	@Test
	void sameMessageDeliveredTwiceConcurrentlyRunsTheProcessorOnce() {
		Event event = insertReceived();
		String eventId = event.eventId();
		// The processor holds the first claim open until the second delivery has tried to claim, so both
		// deliveries are in flight at once rather than one after the other.
		CountDownLatch bothClaimsMade = new CountDownLatch(2);
		AtomicBoolean deliveriesOverlapped = new AtomicBoolean();
		doAnswer(invocation -> {
			try {
				return invocation.callRealMethod();
			}
			finally {
				bothClaimsMade.countDown();
			}
		}).when(stateStore).claim(eventId);
		doAnswer(invocation -> {
			deliveriesOverlapped.set(bothClaimsMade.await(10, TimeUnit.SECONDS));
			return invocation.callRealMethod();
		}).when(processor).process(argThat(e -> e != null && e.eventId().equals(eventId)));

		sqsTemplate.sendMany(properties.queues().events(), List.of(
				MessageBuilder.withPayload(new EventMessage(eventId)).build(),
				MessageBuilder.withPayload(new EventMessage(eventId)).build()));

		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(repository.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.PROCESSED));
		awaitQueueEmpty();
		assertThat(deliveriesOverlapped).isTrue();
		verify(stateStore, times(2)).claim(eventId);
		verify(processor, times(1)).process(argThat(e -> e != null && e.eventId().equals(eventId)));
		assertThat(repository.findById(eventId).orElseThrow().attempts()).isEqualTo(1);
	}

	@Test
	void plainJsonBodyFromAnyProducerIsProcessed() {
		String eventId = insertReceived().eventId();

		sqsClient.sendMessage(request -> request.queueUrl(queueUrl()).messageBody("{\"eventId\":\"%s\"}".formatted(eventId)))
				.join();

		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(repository.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.PROCESSED));
	}

	private void awaitQueueEmpty() {
		await().atMost(TIMEOUT).untilAsserted(() -> {
			Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(request -> request
					.queueUrl(queueUrl())
					.attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
							QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
					.join().attributes();
			assertThat(attributes).containsEntry(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")
					.containsEntry(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0");
		});
	}

	private String queueUrl() {
		return sqsClient.getQueueUrl(request -> request.queueName(properties.queues().events())).join().queueUrl();
	}

	private Event insertReceived() {
		Event event = Event.received(new NewEvent(eventId(), "customer-1", EventType.PAYMENT, new BigDecimal("10.00"),
				"ILS", Instant.parse("2026-09-13T20:15:00Z"), Map.of()), Instant.now());
		repository.insert(event);
		return event;
	}

	private void post(String eventId, String amount) {
		client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).body("""
				{"eventId":"%s","customerId":"customer-1","type":"PAYMENT","amount":%s,"currency":"ILS",\
				"timestamp":"2026-09-13T20:15:00Z"}""".formatted(eventId, amount))
				.exchange()
				.expectStatus().isAccepted();
	}

	private static String eventId() {
		return "evt-" + UUID.randomUUID();
	}
}
