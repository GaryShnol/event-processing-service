package com.shnol.events.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.shnol.events.IntegrationTest;
import com.shnol.events.domain.Event;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoUnavailableTest extends IntegrationTest {

	// The test containers replace this URI through @ServiceConnection, so it is read from config directly.
	@Value("${spring.mongodb.uri}")
	private String configuredUri;

	@Test
	void unreachableMongoFailsWithinTheConfiguredTimeoutAsResourceFailure() {
		MongoClientSettings settings = MongoClientSettings.builder()
				.applyConnectionString(new ConnectionString(configuredUri))
				.applyToClusterSettings(cluster -> cluster.hosts(List.of(new ServerAddress("localhost", 1))))
				.build();

		try (MongoClient client = MongoClients.create(settings)) {
			MongoTemplate template = new MongoTemplate(client, "events");
			long start = System.nanoTime();

			assertThatExceptionOfType(DataAccessResourceFailureException.class)
					.isThrownBy(() -> template.findById("evt-1", Event.class));

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(10));
		}
	}
}
