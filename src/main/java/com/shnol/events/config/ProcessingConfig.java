package com.shnol.events.config;

import com.shnol.events.domain.EventProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProcessingConfig {

	// Created here so the domain class stays free of the app's configuration types.
	@Bean
	EventProcessor eventProcessor(EventsProperties properties) {
		return new EventProcessor(properties.maxAmount());
	}
}
