package com.shnol.events.persistence;

import com.shnol.events.domain.Event;
import com.shnol.events.domain.EventStatus;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/** Creates the indexes before the app serves requests, so they are defined in code rather than by hand. */
@Component
class IndexInitializer implements InitializingBean {

	static final String CUSTOMER_INDEX = "customer_occurredAt_id_status";
	static final String SWEEPER_INDEX = "sweeper_status_lastPublishedAt";

	private final MongoTemplate mongoTemplate;

	IndexInitializer(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public void afterPropertiesSet() {
		IndexOperations indexes = mongoTemplate.indexOps(Event.class);
		indexes.createIndex(new Index()
				.on("customerId", Direction.ASC)
				.on("occurredAt", Direction.DESC)
				.on("_id", Direction.DESC)
				.on("status", Direction.ASC)
				.named(CUSTOMER_INDEX));
		indexes.createIndex(new Index()
				.on("status", Direction.ASC)
				.on("lastPublishedAt", Direction.ASC)
				.partial(PartialIndexFilter.of(Criteria.where("status")
						.in(EventStatus.RECEIVED.name(), EventStatus.PROCESSING.name())))
				.named(SWEEPER_INDEX));
	}
}
