package com.shnol.events.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.shnol.events.IntegrationTest;
import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventProcessor;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.persistence.EventRepository;
import java.time.Duration;
import java.util.List;
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
@TestPropertySource(properties = {
		"events.queues.events=fast-events-queue",
		"events.queues.dead-letter=fast-events-dlq",
		"events.lease=1s" })
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
		client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).body("""
				{"eventId":"%s","customerId":"customer-1","type":"PAYMENT","amount":10.00,"currency":"ILS",\
				"timestamp":"2026-09-13T20:15:00Z"}""".formatted(eventId))
				.exchange()
				.expectStatus().isAccepted();
	}

	private static String eventId() {
		return "evt-" + UUID.randomUUID();
	}
}
