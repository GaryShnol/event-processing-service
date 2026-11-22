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
import com.shnol.events.domain.Event;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
	void mongoThatStopsAnsweringOnAnOpenConnectionFailsWithinTheOperationTimeout() throws IOException {
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

	/** Forwards TCP to MongoDB until stalled, then drops bytes both ways: the connection stays open but never answers. */
	private static final class StallingProxy implements AutoCloseable {

		private final ServerSocket server = new ServerSocket(0);
		private final AtomicBoolean stalled = new AtomicBoolean();
		private final List<Socket> sockets = new CopyOnWriteArrayList<>();

		StallingProxy(String host, int port) throws IOException {
			Thread.ofVirtual().start(() -> {
				while (!server.isClosed()) {
					try {
						Socket client = server.accept();
						Socket upstream = new Socket(host, port);
						sockets.addAll(List.of(client, upstream));
						pump(client, upstream);
						pump(upstream, client);
					}
					catch (IOException e) {
						return; // the proxy was closed
					}
				}
			});
		}

		int port() {
			return server.getLocalPort();
		}

		void stall() {
			stalled.set(true);
		}

		private void pump(Socket from, Socket to) {
			Thread.ofVirtual().start(() -> {
				byte[] buffer = new byte[8192];
				try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
					for (int read; (read = in.read(buffer)) != -1;) {
						if (!stalled.get()) {
							out.write(buffer, 0, read);
						}
					}
				}
				catch (IOException e) {
					// a socket was closed: this direction of the connection is over
				}
			});
		}

		@Override
		public void close() throws IOException {
			server.close();
			for (Socket socket : sockets) {
				socket.close();
			}
		}
	}
}
