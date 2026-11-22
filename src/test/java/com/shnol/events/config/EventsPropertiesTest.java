package com.shnol.events.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EventsPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues(
					"events.queues.events=events-queue",
					"events.publish-timeout=3s",
					"events.lease=60s",
					"events.max-attempts=3",
					"events.max-amount=10000.00",
					"events.sweeper.interval=30s",
					"events.sweeper.republish-after=2m",
					"events.sweeper.batch-size=100",
					"events.paging.default-size=20",
					"events.paging.max-size=100",
					"events.paging.max-offset=10000",
					"events.metadata.max-entries=20",
					"events.metadata.max-key-length=64",
					"events.metadata.max-value-length=256");

	@Test
	void bindsValidProperties() {
		contextRunner.run(context -> {
			EventsProperties properties = context.getBean(EventsProperties.class);
			assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(60));
			assertThat(properties.sweeper().republishAfter()).isEqualTo(Duration.ofMinutes(2));
			assertThat(properties.paging().maxSize()).isEqualTo(100);
		});
	}

	@Test
	void rejectsZeroMaxAttempts() {
		assertRejected("events.max-attempts=0", "maxAttempts");
	}

	@Test
	void rejectsBlankQueueName() {
		assertRejected("events.queues.events=", "queues.events");
	}

	@Test
	void rejectsPublishTimeoutShorterThan100Milliseconds() {
		assertRejected("events.publish-timeout=50ms", "publishTimeout");
	}

	@Test
	void rejectsLeaseShorterThanOneSecond() {
		assertRejected("events.lease=0s", "lease");
	}

	@Test
	void rejectsNonPositiveMaxAmount() {
		assertRejected("events.max-amount=0", "maxAmount");
	}

	@Test
	void rejectsDefaultPageSizeAboveMaximum() {
		assertRejected("events.paging.default-size=101", "paging.defaultSizeWithinMax");
	}

	@Test
	void rejectsZeroMetadataEntries() {
		assertRejected("events.metadata.max-entries=0", "metadata.maxEntries");
	}

	private void assertRejected(String property, String field) {
		contextRunner.withPropertyValues(property).run(context -> assertThat(context)
				.getFailure()
				.rootCause()
				.isInstanceOf(BindValidationException.class)
				.hasMessageContaining("'" + field + "'"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(EventsProperties.class)
	static class PropertiesConfiguration {
	}
}
