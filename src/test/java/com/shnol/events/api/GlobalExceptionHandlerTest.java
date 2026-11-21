package com.shnol.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.shnol.events.application.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(EventController.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private EventService service;

	@Test
	void unreachableDatabaseIsServiceUnavailable() {
		when(service.get("evt-1")).thenThrow(new DataAccessResourceFailureException("Timed out connecting to mongo:27017"));

		assertThat(mvc.get().uri("/api/events/evt-1"))
				.hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyJson()
				.hasPathSatisfying("$.detail", detail -> assertThat(detail).asString().isEqualTo("The service is temporarily unavailable"))
				.satisfies(body -> assertThat(body.getJson()).doesNotContain("mongo:27017"));
	}

	@Test
	void unexpectedErrorIsInternalServerErrorWithoutInternals() {
		when(service.get("evt-1")).thenThrow(new IllegalStateException("secret internal detail"));

		assertThat(mvc.get().uri("/api/events/evt-1"))
				.hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyJson()
				.satisfies(body -> assertThat(body.getJson())
						.doesNotContain("secret internal detail")
						.doesNotContain("IllegalStateException")
						.doesNotContain("trace"));
	}
}
