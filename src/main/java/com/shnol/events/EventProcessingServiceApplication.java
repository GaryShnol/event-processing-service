package com.shnol.events;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EventProcessingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventProcessingServiceApplication.class, args);
	}

}
