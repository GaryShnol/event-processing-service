package com.shnol.events.api;

import com.shnol.events.domain.Event;
import java.util.List;
import org.springframework.data.domain.Page;

public record EventPageResponse(List<EventResponse> content, int page, int size, long totalElements, int totalPages) {

	static EventPageResponse from(Page<Event> page) {
		return new EventPageResponse(page.map(EventResponse::from).getContent(), page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}
}
