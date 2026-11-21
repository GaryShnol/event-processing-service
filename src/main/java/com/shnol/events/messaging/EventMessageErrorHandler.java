package com.shnol.events.messaging;

import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

/**
 * Logs a failed message with its eventId, then rethrows so the message stays unacknowledged and SQS
 * redelivers it after the visibility timeout. The listener container picks this bean up.
 */
@Component
class EventMessageErrorHandler implements ErrorHandler<Object> {

	private static final Logger log = LoggerFactory.getLogger(EventMessageErrorHandler.class);

	@Override
	public void handle(Message<Object> message, Throwable t) {
		// The payload is already an EventMessage: the framework converts the body before the listener runs
		// and skips, without acknowledging, a body that fails conversion, so such a body never gets here.
		EventMessage eventMessage = (EventMessage) message.getPayload();
		try (var ignored = MDC.putCloseable("eventId", eventMessage.eventId())) {
			// The cause as text, not as a throwable: the framework logs the stack trace itself right after.
			log.warn("Processing failed, the message will be redelivered: {}",
					NestedExceptionUtils.getMostSpecificCause(t).toString());
		}
		ReflectionUtils.rethrowRuntimeException(t);
	}
}
