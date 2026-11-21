package com.shnol.events;

import org.springframework.boot.SpringApplication;

public class TestEventProcessingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(EventProcessingServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
