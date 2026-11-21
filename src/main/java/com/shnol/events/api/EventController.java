package com.shnol.events.api;

import com.shnol.events.application.EventService;
import com.shnol.events.domain.EventStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api")
class EventController {

	private final EventService service;

	EventController(EventService service) {
		this.service = service;
	}

	@PostMapping("/events")
	ResponseEntity<CreateEventResponse> create(@Valid @RequestBody CreateEventRequest request) {
		EventStatus status = service.create(request.toNewEvent());
		URI location = UriComponentsBuilder.fromPath("/api/events/{eventId}").buildAndExpand(request.eventId()).toUri();
		return ResponseEntity.accepted().location(location).body(new CreateEventResponse(request.eventId(), status));
	}

	@GetMapping("/events/{eventId}")
	EventResponse get(@PathVariable String eventId) {
		return EventResponse.from(service.get(eventId));
	}

	@GetMapping("/customers/{customerId}/events")
	EventPageResponse findForCustomer(
			@PathVariable String customerId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) EventStatus status,
			@RequestParam(defaultValue = "0") @PositiveOrZero int page,
			@RequestParam(required = false) @Positive Integer size) {
		return EventPageResponse.from(service.findForCustomer(customerId, from, to, status, page, size));
	}
}
