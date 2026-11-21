package com.shnol.events.api;

import com.shnol.events.domain.EventStatus;

public record CreateEventResponse(String eventId, EventStatus status) {
}
