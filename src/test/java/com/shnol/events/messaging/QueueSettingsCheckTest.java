package com.shnol.events.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.shnol.events.IntegrationTest;
import com.shnol.events.config.EventsProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.json.JsonMapper;

class QueueSettingsCheckTest extends IntegrationTest {

	@Autowired
	private SqsAsyncClient sqsClient;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private EventsProperties properties;

	@Test
	void passesWithDefaultLeaseAndAttemptsAgainstConfiguredQueue() {
		assertThatCode(() -> check(properties).afterPropertiesSet()).doesNotThrowAnyException();
	}

	@Test
	void refusesLeaseEqualToVisibilityTimeout() {
		EventsProperties withLongLease = new EventsProperties(properties.queues(), properties.publishTimeout(),
				Duration.ofSeconds(90), properties.maxAttempts(), properties.maxAmount(), properties.sweeper(),
				properties.paging(), properties.metadata());

		assertThatIllegalStateException().isThrownBy(() -> check(withLongLease).afterPropertiesSet())
				.withMessage("events.lease (90 s) must be shorter than the visibility timeout of queue events-queue (90 s)");
	}

	@Test
	void refusesMaxAttemptsEqualToMaxReceiveCount() {
		EventsProperties withManyAttempts = new EventsProperties(properties.queues(), properties.publishTimeout(),
				properties.lease(), 5, properties.maxAmount(), properties.sweeper(), properties.paging(),
				properties.metadata());

		assertThatIllegalStateException().isThrownBy(() -> check(withManyAttempts).afterPropertiesSet())
				.withMessage("events.max-attempts (5) must be lower than the maxReceiveCount of queue events-queue (5)");
	}

	@Test
	void refusesQueueWithoutRedrivePolicy() {
		EventsProperties pointingAtDeadLetterQueue = new EventsProperties(
				new EventsProperties.Queues("events-dlq"), properties.publishTimeout(), Duration.ofSeconds(10),
				properties.maxAttempts(), properties.maxAmount(), properties.sweeper(), properties.paging(),
				properties.metadata());

		assertThatIllegalStateException().isThrownBy(() -> check(pointingAtDeadLetterQueue).afterPropertiesSet())
				.withMessage("Queue events-dlq has no redrive policy with a maxReceiveCount");
	}

	private QueueSettingsCheck check(EventsProperties eventsProperties) {
		return new QueueSettingsCheck(sqsClient, jsonMapper, eventsProperties);
	}

}
