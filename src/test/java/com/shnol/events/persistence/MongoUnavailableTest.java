package com.shnol.events.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterConnectionMode;
import com.shnol.events.IntegrationTest;
import com.shnol.events.StallingProxy;
import com.shnol.events.domain.Event;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

class MongoUnavailableTest extends IntegrationTest {

	// The test containers replace this URI through @ServiceConnection, so it is read from config directly.
	@Value("${spring.mongodb.uri}")
	private String configuredUri;

	@Autowired
	private MongoDBContainer mongo;

	// Both cases end in a MongoTimeoutException, which GlobalExceptionHandler answers with 503.

	@Test
	void unreachableMongoFailsWithinTheServerSelectionTimeout() {
		try (MongoClient client = MongoClients.create(settingsFor(new ServerAddress("localhost", 1)))) {
			MongoTemplate template = new MongoTemplate(client, "events");
			long start = System.nanoTime();

			assertThatExceptionOfType(DataAccessException.class)
					.isThrownBy(() -> template.findById("evt-1", Event.class))
					.withCauseInstanceOf(MongoTimeoutException.class);

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(10));
		}
	}

	@Test
	void mongoThatStopsAnsweringOnAnOpenConnectionFailsWithinTheOperationTimeout() throws Exception {
		try (StallingProxy proxy = new StallingProxy(mongo.getHost(), mongo.getFirstMappedPort());
				MongoClient client = MongoClients.create(settingsFor(new ServerAddress("localhost", proxy.port())))) {
			MongoTemplate template = new MongoTemplate(client, "events");
			template.findById("evt-1", Event.class);
			proxy.stall();
			long start = System.nanoTime();

			// Without an operation timeout the read never returns, so a hang fails the test instead of blocking the build.
			assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThatExceptionOfType(DataAccessException.class)
					.isThrownBy(() -> template.findById("evt-1", Event.class))
					.withCauseInstanceOf(MongoTimeoutException.class));

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(15));
		}
	}

	private MongoClientSettings settingsFor(ServerAddress address) {
		// A direct connection, so the driver doesn't switch to the address the replica set advertises.
		return MongoClientSettings.builder()
				.applyConnectionString(new ConnectionString(configuredUri))
				.applyToClusterSettings(cluster -> cluster.hosts(List.of(address)).mode(ClusterConnectionMode.SINGLE))
				.build();
	}
}
