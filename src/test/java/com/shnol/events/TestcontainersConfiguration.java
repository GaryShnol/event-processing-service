package com.shnol.events;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	private static final int ELASTICMQ_PORT = 9324;

	@Bean
	@ServiceConnection
	MongoDBContainer mongoDbContainer() {
		return new MongoDBContainer(DockerImageName.parse("mongo:8.0.38"));
	}

	@Bean
	GenericContainer<?> elasticMqContainer() {
		return new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
				.withCopyFileToContainer(MountableFile.forHostPath("elasticmq.conf"), "/opt/elasticmq-production.conf")
				.withCopyFileToContainer(MountableFile.forClasspathResource("elasticmq-test.conf"), "/opt/elasticmq.conf")
				.withExposedPorts(ELASTICMQ_PORT)
				.waitingFor(Wait.forLogMessage(".*ElasticMQ server.*started.*", 1));
	}

	// Spring Cloud AWS only offers @ServiceConnection for LocalStack, so ElasticMQ is wired by property.
	@Bean
	DynamicPropertyRegistrar elasticMqProperties(GenericContainer<?> elasticMqContainer) {
		return registry -> {
			registry.add("spring.cloud.aws.sqs.endpoint", () -> "http://%s:%d".formatted(
					elasticMqContainer.getHost(), elasticMqContainer.getMappedPort(ELASTICMQ_PORT)));
			registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
			registry.add("spring.cloud.aws.credentials.access-key", () -> "local");
			registry.add("spring.cloud.aws.credentials.secret-key", () -> "local");
		};
	}

}
