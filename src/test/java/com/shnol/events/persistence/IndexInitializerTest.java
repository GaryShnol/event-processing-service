package com.shnol.events.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shnol.events.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class IndexInitializerTest extends IntegrationTest {

	@Autowired
	private MongoTemplate mongoTemplate;

	@Test
	void createsCustomerIndexWithKeysInQueryOrder() {
		Document index = indexes().get(IndexInitializer.CUSTOMER_INDEX);

		assertThat(index.get("key", Document.class)).containsExactly(
				Map.entry("customerId", 1), Map.entry("occurredAt", -1), Map.entry("_id", -1), Map.entry("status", 1));
		assertThat(index).doesNotContainKey("partialFilterExpression");
	}

	@Test
	void createsSweeperIndexPartialOnUnfinishedStatuses() {
		Document index = indexes().get(IndexInitializer.SWEEPER_INDEX);

		assertThat(index.get("key", Document.class)).containsExactly(Map.entry("status", 1), Map.entry("lastPublishedAt", 1));
		assertThat(index.get("partialFilterExpression", Document.class))
				.isEqualTo(new Document("status", new Document("$in", List.of("RECEIVED", "PROCESSING"))));
	}

	@Test
	void createsNoOtherIndexes() {
		assertThat(indexes()).containsOnlyKeys("_id_", IndexInitializer.CUSTOMER_INDEX, IndexInitializer.SWEEPER_INDEX);
	}

	private Map<String, Document> indexes() {
		return mongoTemplate.getCollection("events").listIndexes().into(new ArrayList<>()).stream()
				.collect(Collectors.toMap(index -> index.getString("name"), index -> index));
	}
}
