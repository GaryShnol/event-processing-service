package com.shnol.events.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.shnol.events.IntegrationTest;
import com.shnol.events.config.EventsProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

// The listener is off so the test can read the message off the queue itself.
@TestPropertySource(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class SqsEventPublisherTest extends IntegrationTest {

	@Autowired
	private EventPublisher publisher;

	@Autowired
	private SqsAsyncClient sqsClient;

	@Autowired
	private EventsProperties properties;

	@Test
	void publishSendsOnlyTheEventIdAsJsonToTheEventsQueue() {
		String eventId = "evt-" + UUID.randomUUID();

		publisher.publish(eventId);

		assertThat(receiveAll()).extracting(Message::body).contains("{\"eventId\":\"%s\"}".formatted(eventId));
	}

	private List<Message> receiveAll() {
		String queueUrl = sqsClient.getQueueUrl(request -> request.queueName(properties.queues().events())).join().queueUrl();
		return sqsClient.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(5))
				.join().messages();
	}
}
