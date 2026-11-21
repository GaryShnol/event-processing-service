package com.shnol.events.messaging;

import com.shnol.events.config.EventsProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses to start when the lease or attempt limit doesn't fit the queue's own settings: a lease
 * at least as long as the visibility timeout lets a redelivery find the old claim still held, and
 * maxAttempts at or above maxReceiveCount lets the DLQ take a message before MongoDB marks it FAILED.
 */
@Component
class QueueSettingsCheck implements InitializingBean {

	private final SqsAsyncClient sqsClient;
	private final JsonMapper jsonMapper;
	private final EventsProperties properties;

	QueueSettingsCheck(SqsAsyncClient sqsClient, JsonMapper jsonMapper, EventsProperties properties) {
		this.sqsClient = sqsClient;
		this.jsonMapper = jsonMapper;
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		String queueName = properties.queues().events();
		String queueUrl = sqsClient.getQueueUrl(request -> request.queueName(queueName)).join().queueUrl();
		Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(request -> request
				.queueUrl(queueUrl)
				.attributeNames(QueueAttributeName.VISIBILITY_TIMEOUT, QueueAttributeName.REDRIVE_POLICY))
				.join().attributes();

		Duration visibilityTimeout = Duration.ofSeconds(Long.parseLong(attributes.get(QueueAttributeName.VISIBILITY_TIMEOUT)));
		if (properties.lease().compareTo(visibilityTimeout) >= 0) {
			throw new IllegalStateException(
					"events.lease (%d s) must be shorter than the visibility timeout of queue %s (%d s)"
							.formatted(properties.lease().toSeconds(), queueName, visibilityTimeout.toSeconds()));
		}

		String redrivePolicy = attributes.get(QueueAttributeName.REDRIVE_POLICY);
		int maxReceiveCount = redrivePolicy == null ? 0
				: jsonMapper.readTree(redrivePolicy).path("maxReceiveCount").asInt(0);
		if (maxReceiveCount <= 0) {
			throw new IllegalStateException("Queue %s has no redrive policy with a maxReceiveCount".formatted(queueName));
		}
		if (properties.maxAttempts() >= maxReceiveCount) {
			throw new IllegalStateException(
					"events.max-attempts (%d) must be lower than the maxReceiveCount of queue %s (%d)"
							.formatted(properties.maxAttempts(), queueName, maxReceiveCount));
		}
	}

}
