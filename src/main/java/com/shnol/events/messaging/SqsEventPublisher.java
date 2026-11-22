package com.shnol.events.messaging;

import com.shnol.events.application.EventPublisher;
import com.shnol.events.config.EventsProperties;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class SqsEventPublisher implements EventPublisher {

	private final SqsTemplate sqsTemplate;
	private final String queueName;
	private final Duration timeout;

	SqsEventPublisher(SqsTemplate sqsTemplate, EventsProperties properties) {
		this.sqsTemplate = sqsTemplate;
		this.queueName = properties.queues().events();
		this.timeout = properties.publishTimeout();
	}

	@Override
	public void publish(String eventId) {
		// The timeout is on this send, not on the shared SQS client: a client-wide timeout shorter than the
		// listener's long-poll wait would cut off receiving. The SDK call isn't cancelled: it holds a connection
		// until the SDK gives up, and may still deliver late, which is harmless because duplicates are expected.
		sqsTemplate.sendAsync(queueName, new EventMessage(eventId)).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
	}
}
