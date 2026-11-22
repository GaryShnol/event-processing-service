package com.shnol.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shnol.events.IntegrationTest;
import com.shnol.events.application.EventPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

class EventApiTest extends IntegrationTest {

	@Autowired
	private RestTestClient client;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@MockitoBean
	private EventPublisher publisher;

	@Test
	void createReturnsAcceptedWithLocationAndPublishesOnce() {
		String eventId = eventId();

		post(json(eventId, "customer-1", "1250.50", "2026-09-13T20:15:00Z"))
				.expectStatus().isAccepted()
				.expectHeader().location("/api/events/" + eventId)
				.expectBody()
				.json("{\"eventId\":\"%s\",\"status\":\"RECEIVED\"}".formatted(eventId), JsonCompareMode.STRICT);

		verify(publisher).publish(eventId);
	}

	@Test
	void twentyConcurrentPostsWithOneEventIdStoreOneDocumentAndPublishOnce() throws Exception {
		String eventId = eventId();
		String body = json(eventId, "customer-1", "10.00", "2026-09-13T20:15:00Z");
		int requests = 20;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> responses = new ArrayList<>();

		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				responses.add(executor.submit(() -> {
					start.await();
					return post(body).returnResult(String.class).getStatus().value();
				}));
			}
			start.countDown();
			for (Future<Integer> response : responses) {
				assertThat(response.get()).isEqualTo(HttpStatus.ACCEPTED.value());
			}
		}

		assertThat(mongoTemplate.getCollection("events").countDocuments(new Document("_id", eventId))).isEqualTo(1);
		verify(publisher, times(1)).publish(eventId);
	}

	@Test
	void repeatWithSamePayloadReturnsCurrentStatusEvenWhenAmountScaleDiffers() {
		String eventId = eventId();
		post(json(eventId, "customer-1", "1250.5", "2026-09-13T20:15:00Z")).expectStatus().isAccepted();

		post(json(eventId, "customer-1", "1250.50", "2026-09-13T20:15:00Z"))
				.expectStatus().isAccepted()
				.expectHeader().location("/api/events/" + eventId)
				.expectBody().jsonPath("$.status").isEqualTo("RECEIVED");

		verify(publisher, times(1)).publish(eventId);
	}

	@Test
	void repeatWithDifferentPayloadIsConflict() {
		String eventId = eventId();
		post(json(eventId, "customer-1", "1250.50", "2026-09-13T20:15:00Z")).expectStatus().isAccepted();

		post(json(eventId, "customer-1", "1250.51", "2026-09-13T20:15:00Z"))
				.expectStatus().isEqualTo(HttpStatus.CONFLICT)
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.status").isEqualTo(409);

		client.get().uri("/api/events/{id}", eventId).exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.amount").isEqualTo(1250.50);
		verify(publisher, times(1)).publish(eventId);
	}

	@Test
	void getReturnsExactlyTheDocumentedFields() {
		String eventId = eventId();
		post(json(eventId, "customer-1", "1250.5", "2026-09-13T20:15:00Z")).expectStatus().isAccepted();

		String body = client.get().uri("/api/events/{id}", eventId).exchange()
				.expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();

		Map<String, Object> event = jsonMapper.readValue(body, Map.class);
		assertThat(event).containsOnlyKeys("eventId", "customerId", "type", "amount", "currency", "status",
				"timestamp", "createdAt", "processedAt", "failureReason");
		assertThat(event).containsEntry("eventId", eventId).containsEntry("customerId", "customer-1")
				.containsEntry("type", "PAYMENT").containsEntry("currency", "ILS").containsEntry("status", "RECEIVED")
				.containsEntry("timestamp", "2026-09-13T20:15:00Z").containsEntry("processedAt", null)
				.containsEntry("failureReason", null);
		assertThat(body).contains("\"amount\":1250.50");
		assertThat(event.get("createdAt")).isNotNull();
	}

	@Test
	void unknownEventIsNotFound() {
		client.get().uri("/api/events/{id}", eventId()).exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.status").isEqualTo(404);
	}

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource(delimiter = '|', value = {
			"eventId    | \"evt 1\"",
			"eventId    | null",
			"eventId    | \"\"",
			"eventId    | \"evt-0123456789012345678901234567890123456789012345678901234567890\"",
			"customerId | \"\"",
			"customerId | \"customer/1\"",
			"type       | \"TRANSFER\"",
			"amount     | 0",
			"amount     | -5",
			"amount     | 10.001",
			"amount     | \"ten\"",
			"amount     | 1234567890123456.00",
			"currency   | \"ABC\"",
			"currency   | \"ils\"",
			"timestamp  | null",
			"timestamp  | \"yesterday\"",
			"timestamp  | \"1969-12-31T23:59:59.999Z\"",
			"timestamp  | \"+10000-01-01T00:00:00Z\"",
			"timestamp  | \"+1000000000-12-31T23:59:59.999999999Z\"",
			"metadata   | {\"a.b\":\"x\"}",
			"metadata   | {\"source\":null}",
			"metadata   | {\"k0123456789012345678901234567890123456789012345678901234567890123\":\"x\"}",
			"metadata   | {\"source\":\"%s\"}",
	})
	void invalidFieldIsRejectedWithTheFieldNamed(String field, String value) {
		Map<String, String> fields = new LinkedHashMap<>(Map.of(
				"eventId", "\"" + eventId() + "\"", "customerId", "\"customer-1\"", "type", "\"PAYMENT\"",
				"amount", "10.00", "currency", "\"ILS\"", "timestamp", "\"2026-09-13T20:15:00Z\"",
				"metadata", "{\"source\":\"mobile\"}"));
		fields.put(field, value.replace("%s", "x".repeat(257)));
		String body = fields.entrySet().stream()
				.map(entry -> "\"%s\":%s".formatted(entry.getKey(), entry.getValue()))
				.collect(Collectors.joining(",", "{", "}"));

		post(body).expectStatus().isBadRequest()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo(field)
				.jsonPath("$.errors[0].message").isNotEmpty();
		verify(publisher, never()).publish(any());
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "1970-01-01T00:00:00Z", "9999-12-31T23:59:59.999Z" })
	void timestampAtTheEdgeOfTheStorableRangeIsAcceptedAndReadBack(String timestamp) {
		String eventId = eventId();

		post(json(eventId, "customer-1", "10.00", timestamp)).expectStatus().isAccepted();

		client.get().uri("/api/events/{id}", eventId).exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.timestamp").isEqualTo(timestamp);
	}

	@Test
	void metadataWithTooManyEntriesIsRejected() {
		String metadata = IntStream.range(0, 21).mapToObj(i -> "\"k%d\":\"v\"".formatted(i))
				.collect(Collectors.joining(",", "{", "}"));
		String body = json(eventId(), "customer-1", "10.00", "2026-09-13T20:15:00Z")
				.replace("{\"source\":\"mobile\",\"channel\":\"app\"}", metadata);

		post(body).expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[0].field").isEqualTo("metadata")
				.jsonPath("$.errors[0].message").isEqualTo("must have at most 20 entries");
	}

	@Test
	void malformedJsonIsBadRequestWithoutInternals() {
		post("{\"eventId\":").expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Malformed request body")
				.jsonPath("$.trace").doesNotExist();
	}

	@Test
	void customerQueryFiltersByDateRangeAndStatusAndPagesNewestFirst() {
		String customerId = "customer-" + UUID.randomUUID();
		String sep1 = createFor(customerId, "2026-09-01T10:00:00Z");
		String sep2 = createFor(customerId, "2026-09-02T10:00:00Z");
		String sep3 = createFor(customerId, "2026-09-03T10:00:00Z");
		createFor(customerId, "2026-10-01T10:00:00Z");
		createFor("customer-" + UUID.randomUUID(), "2026-09-02T10:00:00Z");

		String range = "from=2026-09-01T10:00:00Z&to=2026-09-03T10:00:00Z";
		client.get().uri("/api/customers/{id}/events?" + range + "&size=2", customerId).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.content[*].eventId").isEqualTo(List.of(sep3, sep2))
				.jsonPath("$.page").isEqualTo(0)
				.jsonPath("$.size").isEqualTo(2)
				.jsonPath("$.totalElements").isEqualTo(3)
				.jsonPath("$.totalPages").isEqualTo(2);
		client.get().uri("/api/customers/{id}/events?" + range + "&size=2&page=1", customerId).exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.content[*].eventId").isEqualTo(List.of(sep1));
		client.get().uri("/api/customers/{id}/events?" + range + "&status=RECEIVED", customerId).exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.totalElements").isEqualTo(3).jsonPath("$.size").isEqualTo(20);
		client.get().uri("/api/customers/{id}/events?" + range + "&status=PROCESSED", customerId).exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.content").isEmpty().jsonPath("$.totalElements").isEqualTo(0);
	}

	@Test
	void unknownCustomerGetsAnEmptyPage() {
		client.get().uri("/api/customers/{id}/events", "customer-" + UUID.randomUUID()).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.content").isEmpty()
				.jsonPath("$.totalElements").isEqualTo(0);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"size=101                                          | size",
			"size=0                                            | size",
			"page=-1                                           | page",
			"from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z | from",
			"from=yesterday                                    | from",
			"status=DONE                                       | status",
	})
	void invalidCustomerQueryIsRejectedWithTheParameterNamed(String query, String parameter) {
		client.get().uri("/api/customers/customer-1/events?" + query).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.errors[0].field").isEqualTo(parameter);
	}

	private String createFor(String customerId, String timestamp) {
		String eventId = eventId();
		post(json(eventId, customerId, "10.00", timestamp)).expectStatus().isAccepted();
		return eventId;
	}

	private RestTestClient.ResponseSpec post(String body) {
		return client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private static String eventId() {
		return "evt-" + UUID.randomUUID();
	}

	private static String json(String eventId, String customerId, String amount, String timestamp) {
		return """
				{"eventId":"%s","customerId":"%s","type":"PAYMENT","amount":%s,"currency":"ILS",\
				"timestamp":"%s","metadata":{"source":"mobile","channel":"app"}}"""
				.formatted(eventId, customerId, amount, timestamp);
	}
}
