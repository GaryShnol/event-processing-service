package com.shnol.events.config;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MongoTimeoutConfig {

	// Set in code, not in the URI, so they hold for any MONGODB_URI. Unordered, this runs after Boot's customizer
	// that applies the URI, so it also wins over timeouts a URI sets.
	@Bean
	MongoClientSettingsBuilderCustomizer mongoTimeouts(EventsProperties properties) {
		EventsProperties.Mongo mongo = properties.mongo();
		return builder -> builder
				.applyToClusterSettings(cluster -> cluster
						.serverSelectionTimeout(mongo.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
				.timeout(mongo.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
	}
}
