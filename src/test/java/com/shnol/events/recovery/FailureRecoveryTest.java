package com.shnol.events.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import com.shnol.events.IntegrationTest;
import com.shnol.events.application.EventPublisher;
import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventProcessor;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.persistence.EventRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

// The fast queue redelivers after 2 s and the lease is 1 s, so a redelivery finds the old claim expired.
// The sweeper runs every second and re-publishes after 5 s, later than a redelivery.
@TestPropertySource(properties = {
		"events.queues.events=fast-events-queue",
		"events.queues.dead-letter=fast-events-dlq",
		"events.lease=1s",
		"events.sweeper.enabled=true",
		"events.sweeper.interval=1s",
		"events.sweeper.republish-after=5s" })
@ExtendWith(OutputCaptureExtension.class)
class FailureRecoveryTest extends IntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private RestTestClient client;

	@Autowired
	private SqsAsyncClient sqsClient;

	@Autowired
	private EventRepository repository;

	@Autowired
	private EventsProperties properties;

	@MockitoSpyBean
	private EventProcessor processor;

	@MockitoSpyBean
	private EventPublisher publisher;

	@Test
	void transientFailureOnTheFirstAttemptIsRedeliveredAndProcessedOnTheSecond(CapturedOutput output) {
		String eventId = eventId();
		doThrow(new DataAccessResourceFailureException("simulated outage")).doCallRealMethod()
				.when(processor).process(argThat(event -> event != null && event.eventId().equals(eventId)));

		post(eventId);

		await().atMost(TIMEOUT).untilAsserted(() -> {
			Event stored = repository.findById(eventId).orElseThrow();
			assertThat(stored.status()).isEqualTo(EventStatus.PROCESSED);
			assertThat(stored.attempts()).isEqualTo(2);
		});
		assertThat(output.getOut()).containsPattern(
				("\"message\":\"Processing failed, the message will be redelivered: "
						+ "org.springframework.dao.DataAccessResourceFailureException: simulated outage\",\"eventId\":\"%s\"")
						.formatted(eventId));
	}

	@Test
	void eventFailingTransientlyOnEveryAttemptEndsFailedWithMaxAttemptsExceeded() {
		String eventId = eventId();
		doThrow(new DataAccessResourceFailureException("simulated outage"))
				.when(processor).process(argThat(event -> event != null && event.eventId().equals(eventId)));

		post(eventId);

		await().atMost(TIMEOUT).untilAsserted(() -> {
			Event stored = repository.findById(eventId).orElseThrow();
			assertThat(stored.status()).isEqualTo(EventStatus.FAILED);
			assertThat(stored.failureReason()).isEqualTo("max attempts exceeded");
			assertThat(stored.attempts()).isEqualTo(properties.maxAttempts());
		});
	}

	@Test
	void processingEventWithExpiredLeaseAndNoMessageIsRepublishedAndProcessed() {
		Instant longAgo = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
		Event received = Event.received(new NewEvent(eventId(), "customer-1", EventType.PAYMENT, new BigDecimal("10.00"),
				"ILS", longAgo, Map.of()), longAgo);
		// A worker claimed it and crashed, and its message is gone: only the sweeper can get it delivered again.
		repository.insert(new Event(received.eventId(), received.customerId(), received.type(), received.amount(),
				received.currency(), received.occurredAt(), received.metadata(), received.payloadHash(),
				EventStatus.PROCESSING, 1, "crashed-worker", longAgo.plusSeconds(60), longAgo, null, longAgo, longAgo,
				null));

		await().atMost(TIMEOUT).untilAsserted(() -> {
			Event stored = repository.findById(received.eventId()).orElseThrow();
			assertThat(stored.status()).isEqualTo(EventStatus.PROCESSED);
			assertThat(stored.attempts()).isEqualTo(2);
		});
	}

	@Test
	void eventWhosePublishFailedIsAcceptedAndLaterPublishedBySweeper(CapturedOutput output) {
		String eventId = eventId();
		doThrow(new IllegalStateException("SQS unavailable")).doCallRealMethod().when(publisher).publish(eventId);

		client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).body(body(eventId))
				.exchange()
				.expectStatus().isAccepted()
				.expectBody().jsonPath("$.status").isEqualTo("RECEIVED");

		assertThat(output.getOut()).containsPattern(
				"\"message\":\"Publish failed, the sweeper will publish the event later\",\"eventId\":\"%s\""
						.formatted(eventId));
		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(repository.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.PROCESSED));
		verify(publisher, atLeast(2)).publish(eventId);
	}

	@Test
	void unparseableMessageLandsInTheDeadLetterQueue() {
		String body = "not json " + UUID.randomUUID();

		send(body);

		awaitInDeadLetterQueue(body);
	}

	@Test
	void messageWithoutEventIdLandsInTheDeadLetterQueue() {
		String body = "{\"note\":\"%s\"}".formatted(UUID.randomUUID());

		send(body);

		awaitInDeadLetterQueue(body);
	}

	@Test
	void messageWithBlankEventIdLandsInTheDeadLetterQueue() {
		String body = "{\"eventId\":\" \",\"note\":\"%s\"}".formatted(UUID.randomUUID());

		send(body);

		awaitInDeadLetterQueue(body);
	}

	private void send(String body) {
		sqsClient.sendMessage(request -> request.queueUrl(queueUrl(properties.queues().events())).messageBody(body)).join();
	}

	private void awaitInDeadLetterQueue(String body) {
		String deadLetterUrl = queueUrl(properties.queues().deadLetter());
		await().atMost(TIMEOUT).untilAsserted(() -> {
			List<Message> messages = sqsClient.receiveMessage(request -> request.queueUrl(deadLetterUrl)
					.maxNumberOfMessages(10).visibilityTimeout(0).waitTimeSeconds(1)).join().messages();
			assertThat(messages).extracting(Message::body).contains(body);
		});
	}

	private String queueUrl(String queueName) {
		return sqsClient.getQueueUrl(request -> request.queueName(queueName)).join().queueUrl();
	}

	private void post(String eventId) {
		client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).body(body(eventId))
				.exchange()
				.expectStatus().isAccepted();
	}

	private static String body(String eventId) {
		return """
				{"eventId":"%s","customerId":"customer-1","type":"PAYMENT","amount":10.00,"currency":"ILS",\
				"timestamp":"2026-09-13T20:15:00Z"}""".formatted(eventId);
	}

	private static String eventId() {
		return "evt-" + UUID.randomUUID();
	}
}
