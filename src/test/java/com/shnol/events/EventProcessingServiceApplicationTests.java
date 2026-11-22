package com.shnol.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.shnol.events.config.EventsProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@ExtendWith(OutputCaptureExtension.class)
class EventProcessingServiceApplicationTests extends IntegrationTest {

	@Autowired
	private SqsAsyncClient sqsClient;

	@Autowired
	private HealthEndpoint healthEndpoint;

	@Autowired
	private EventsProperties properties;

	@Test
	void healthIsUpWithMongoDbConnected() {
		assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
		assertThat(healthEndpoint.healthForPath("mongo").getStatus()).isEqualTo(Status.UP);
	}

	@Test
	void eventsQueueRedrivesToDeadLetterQueueAfterFiveReceives() {
		Map<QueueAttributeName, String> eventsQueue = attributes(properties.queues().events());
		Map<QueueAttributeName, String> deadLetterQueue = attributes("events-dlq");

		assertThat(eventsQueue.get(QueueAttributeName.VISIBILITY_TIMEOUT)).isEqualTo("90");
		assertThat(eventsQueue.get(QueueAttributeName.REDRIVE_POLICY))
				.contains(deadLetterQueue.get(QueueAttributeName.QUEUE_ARN))
				.contains("\"maxReceiveCount\":5");
	}

	@Test
	void loadsDefaultsFromApplicationYaml() {
		assertThat(properties.queues().events()).isEqualTo("events-queue");
		assertThat(properties.publishTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(60));
		assertThat(properties.maxAttempts()).isEqualTo(3);
		assertThat(properties.maxAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
		assertThat(properties.sweeper().interval()).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.sweeper().republishAfter()).isEqualTo(Duration.ofMinutes(2));
		assertThat(properties.sweeper().batchSize()).isEqualTo(100);
		assertThat(properties.paging().defaultSize()).isEqualTo(20);
		assertThat(properties.paging().maxSize()).isEqualTo(100);
		assertThat(properties.paging().maxOffset()).isEqualTo(10000);
		assertThat(properties.metadata()).isEqualTo(new EventsProperties.Metadata(20, 64, 256));
	}

	@Test
	void logsAsStructuredJson(CapturedOutput output) {
		LoggerFactory.getLogger(getClass()).info("structured logging check");

		assertThat(output.getOut()).containsPattern(
				"\\{\"@timestamp\":\"[^\"]+\".*\"message\":\"structured logging check\"");
	}

	private Map<QueueAttributeName, String> attributes(String queueName) {
		String queueUrl = sqsClient.getQueueUrl(request -> request.queueName(queueName)).join().queueUrl();
		return sqsClient.getQueueAttributes(request -> request.queueUrl(queueUrl)
				.attributeNames(QueueAttributeName.ALL)).join().attributes();
	}

}
