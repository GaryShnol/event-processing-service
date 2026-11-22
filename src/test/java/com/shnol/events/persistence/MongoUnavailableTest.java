package com.shnol.events.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.internal.MongoClientImpl;
import com.mongodb.connection.ClusterConnectionMode;
import com.shnol.events.IntegrationTest;
import com.shnol.events.StallingProxy;
import com.shnol.events.domain.Event;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

class MongoUnavailableTest extends IntegrationTest {

	// The app's timeouts, applied to a plain URI that carries none, as a production URI might not.
	@Autowired
	@Qualifier("mongoTimeouts")
	private MongoClientSettingsBuilderCustomizer mongoTimeouts;

	@Autowired
	private MongoDBContainer mongo;

	// GlobalExceptionHandler answers a resource failure, or an uncategorized error caused by a MongoTimeoutException,
	// with 503. These tests pin the exception types the driver and Spring Data actually produce.

	@Test
	void appMongoClientGetsBothTimeoutsAlthoughItsUriCarriesNone(@Autowired MongoClient appClient) {
		// The Testcontainers URI has no timeout options. The cast is the only way to read the server selection timeout.
		MongoClientSettings settings = ((MongoClientImpl) appClient).getSettings();

		assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
		assertThat(settings.getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(10_000);
	}

	@Test
	void unreachableMongoFailsWithinTheServerSelectionTimeout() {
		try (MongoClient client = MongoClients.create(settingsFor(new ServerAddress("localhost", 1)))) {
			MongoTemplate template = new MongoTemplate(client, "events");
			long start = System.nanoTime();

			assertThatThrownBy(() -> template.findById("evt-1", Event.class))
					.isInstanceOfAny(DataAccessResourceFailureException.class, UncategorizedMongoDbException.class)
					.hasCauseInstanceOf(MongoTimeoutException.class);

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
			assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThatThrownBy(() -> template.findById("evt-1", Event.class))
					.isInstanceOfAny(DataAccessResourceFailureException.class, UncategorizedMongoDbException.class)
					.hasCauseInstanceOf(MongoTimeoutException.class));

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(15));
		}
	}

	private MongoClientSettings settingsFor(ServerAddress address) {
		MongoClientSettings.Builder builder = MongoClientSettings.builder()
				.applyConnectionString(new ConnectionString("mongodb://localhost/events"));
		mongoTimeouts.customize(builder);
		// A direct connection, so the driver doesn't switch to the address the replica set advertises.
		return builder.applyToClusterSettings(cluster -> cluster.hosts(List.of(address)).mode(ClusterConnectionMode.SINGLE))
				.build();
	}
}
