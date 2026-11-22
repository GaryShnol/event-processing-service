package com.shnol.events.messaging;

import com.shnol.events.application.EventPublisher;
import com.shnol.events.config.EventsProperties;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.stereotype.Component;

@Component
class SqsEventPublisher implements EventPublisher {

	private final SqsTemplate sqsTemplate;
	private final String queueName;

	SqsEventPublisher(SqsTemplate sqsTemplate, EventsProperties properties) {
		this.sqsTemplate = sqsTemplate;
		this.queueName = properties.queues().events();
	}

	@Override
	public void publish(String eventId) {
		sqsTemplate.send(queueName, new EventMessage(eventId));
	}
}
