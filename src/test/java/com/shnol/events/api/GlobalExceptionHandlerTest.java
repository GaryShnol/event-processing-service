package com.shnol.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mongodb.MongoOperationTimeoutException;
import com.shnol.events.application.EventService;
import com.shnol.events.config.EventsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(EventController.class)
@EnableConfigurationProperties(EventsProperties.class)
@ExtendWith(OutputCaptureExtension.class)
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
	void databaseOperationTimeoutIsServiceUnavailable() {
		when(service.get("evt-1")).thenThrow(new UncategorizedMongoDbException("Operation exceeded the timeout limit",
				new MongoOperationTimeoutException("Operation exceeded the timeout limit")));

		assertThat(mvc.get().uri("/api/events/evt-1")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void otherUncategorizedDatabaseErrorIsInternalServerError() {
		when(service.get("evt-1")).thenThrow(new UncategorizedMongoDbException("something else", new IllegalStateException()));

		assertThat(mvc.get().uri("/api/events/evt-1")).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
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

	@Test
	void failedPostLogsTheErrorWithEventIdAndClearsItAfterTheRequest(CapturedOutput output) {
		when(service.create(any())).thenThrow(new DataAccessResourceFailureException("Timed out connecting to mongo:27017"));

		assertThat(mvc.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
				{"eventId":"evt-mdc-1","customerId":"customer-1","type":"PAYMENT","amount":10.00,"currency":"ILS",\
				"timestamp":"2026-09-13T20:15:00Z"}"""))
				.hasStatus(HttpStatus.SERVICE_UNAVAILABLE);

		assertThat(output.getOut()).containsPattern(
				"(?m)^(?=.*\"message\":\"MongoDB is unavailable\")(?=.*\"eventId\":\"evt-mdc-1\").*$");
		assertThat(MDC.get("eventId")).isNull();
	}
}
