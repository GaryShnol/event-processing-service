package com.shnol.events.messaging;

/** The SQS message body: {"eventId": "..."}. */
record EventMessage(String eventId) {
}
