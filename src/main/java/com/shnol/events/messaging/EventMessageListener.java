package com.shnol.events.messaging;

import com.shnol.events.application.EventProcessingService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Acknowledges a message only when processing returns normally; an exception leaves it on the queue
 * for redelivery after the visibility timeout. spring.cloud.aws.sqs.listener.auto-startup turns
 * consumption off for an API-only deployment.
 */
@Component
class EventMessageListener {

	private final EventProcessingService processingService;

	EventMessageListener(EventProcessingService processingService) {
		this.processingService = processingService;
	}

	@SqsListener(value = "${events.queues.events}", acknowledgementMode = SqsListenerAcknowledgementMode.ON_SUCCESS)
	void onMessage(EventMessage message) {
		try (var ignored = MDC.putCloseable("eventId", message.eventId())) {
			processingService.process(message.eventId());
		}
	}
}
