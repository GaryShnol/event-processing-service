package com.shnol.events.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shnol.events.IntegrationTest;
import com.shnol.events.StallingProxy;
import com.shnol.events.application.EventPublisher;
import com.shnol.events.config.EventsProperties;
import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import com.shnol.events.domain.EventType;
import com.shnol.events.domain.NewEvent;
import com.shnol.events.persistence.EventRepository;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

// The publisher sends to an SQS that accepts connections and never answers. The listener keeps the app's own
// SQS client, whose long polls outlast the publish timeout, so it must go on receiving from the real queue.
@Import(SqsPublishTimeoutTest.SilentSqs.class)
@ExtendWith(OutputCaptureExtension.class)
class SqsPublishTimeoutTest extends IntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	@Autowired
	private RestTestClient client;

	@Autowired
	private SqsTemplate sqsTemplate;

	@Autowired
	private EventRepository repository;

	@Autowired
	private EventsProperties properties;

	@Test
	void stalledPublishEndsAfterThePublishTimeoutAndThePostIsStillAccepted(CapturedOutput output) {
		String eventId = "evt-" + UUID.randomUUID();
		long start = System.nanoTime();

		client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).body("""
				{"eventId":"%s","customerId":"customer-1","type":"PAYMENT","amount":10.00,"currency":"ILS",\
				"timestamp":"2026-09-13T20:15:00Z"}""".formatted(eventId))
				.exchange()
				.expectStatus().isAccepted()
				.expectBody().jsonPath("$.status").isEqualTo("RECEIVED");

		assertThat(Duration.ofNanos(System.nanoTime() - start))
				.isBetween(properties.publishTimeout(), properties.publishTimeout().plusSeconds(3));
		assertThat(output.getOut()).containsPattern(
				"\"message\":\"Publish failed, the sweeper will publish the event later\",\"eventId\":\"%s\""
						.formatted(eventId));
		assertThat(repository.findById(eventId)).isPresent();
	}

	@Test
	void listenerKeepsReceivingFromTheRealQueue() {
		Event event = Event.received(new NewEvent("evt-" + UUID.randomUUID(), "customer-1", EventType.PAYMENT,
				new BigDecimal("10.00"), "ILS", Instant.parse("2026-09-13T20:15:00Z"), Map.of()), Instant.now());
		repository.insert(event);

		sqsTemplate.send(properties.queues().events(), new EventMessage(event.eventId()));

		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(repository.findById(event.eventId()).orElseThrow().status()).isEqualTo(EventStatus.PROCESSED));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SilentSqs {

		@Bean(destroyMethod = "close")
		StallingProxy silentSqs(@Qualifier("elasticMqContainer") GenericContainer<?> elasticMq) throws IOException {
			StallingProxy proxy = new StallingProxy(elasticMq.getHost(), elasticMq.getFirstMappedPort());
			proxy.stall();
			return proxy;
		}

		@Bean
		@Primary
		EventPublisher publisherToSilentSqs(StallingProxy silentSqs, EventsProperties properties) {
			SqsAsyncClient sqsClient = SqsAsyncClient.builder()
					.endpointOverride(URI.create("http://localhost:" + silentSqs.port()))
					.region(Region.US_EAST_1)
					.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
					.build();
			return new SqsEventPublisher(SqsTemplate.newTemplate(sqsClient), properties);
		}
	}
}
