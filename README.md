# event-processing-service

A Spring Boot service that accepts business events (payments and refunds) over REST, stores each one in MongoDB and processes it asynchronously through an SQS queue. A client can safely retry a `POST`, check an event's status, and list a customer's events by date range and status. Duplicate requests and duplicate messages are absorbed, and a crashed worker or a lost message only delays an event. The design is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Prerequisites

- Docker with Docker Compose.
- JDK 21, only to run the tests or to run the app outside Docker. The Maven wrapper downloads Maven.

## Run with Docker

```
docker compose up --build
```

This starts MongoDB, ElasticMQ (a local SQS) with `events-queue` and `events-dlq`, and the app on http://localhost:8080. When the app is ready:

```
curl -s http://localhost:8080/actuator/health
```

```
{"groups":["liveness","readiness"],"status":"UP"}
```

## Run locally

MongoDB and ElasticMQ in Docker, the app on the host:

```
docker compose up -d mongo elasticmq
```

PowerShell:

```
$env:AWS_REGION="us-east-1"; $env:AWS_ACCESS_KEY_ID="local"; $env:AWS_SECRET_ACCESS_KEY="local"; $env:AWS_ENDPOINT_URL_SQS="http://localhost:9324"
.\mvnw.cmd spring-boot:run
```

bash:

```
export AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local AWS_ENDPOINT_URL_SQS=http://localhost:9324
./mvnw spring-boot:run
```

The app has no AWS defaults of its own, so in AWS it uses the task role. ElasticMQ accepts any credentials.

## Run the tests

```
./mvnw verify        # bash
.\mvnw.cmd verify    # PowerShell
```

Docker must be running: the integration tests start MongoDB and ElasticMQ with Testcontainers. `verify` fails if line coverage is below 80%.

## Use the API

The request bodies are in [`examples/`](examples). Run the commands from the repository root. They work as written in bash; in Windows PowerShell write `curl.exe` instead of `curl`, because `curl` there is an alias for `Invoke-WebRequest`. The responses below leave out headers other than the status line and `Location`.

Create an event:

```
curl -s -i -X POST http://localhost:8080/api/events -H "Content-Type: application/json" --data-binary "@examples/payment.json"
```

```
HTTP/1.1 202
Location: /api/events/evt-1001

{"eventId":"evt-1001","status":"RECEIVED"}
```

Get it. Processing usually finishes within a second:

```
curl -s http://localhost:8080/api/events/evt-1001
```

```
{"eventId":"evt-1001","customerId":"customer-42","type":"PAYMENT","amount":1250.50,"currency":"ILS","status":"PROCESSED","timestamp":"2026-11-01T10:15:00Z","createdAt":"2026-11-21T20:56:34.285Z","processedAt":"2026-11-21T20:56:34.379Z","failureReason":null}
```

Send the same request again. A retry is accepted and returns the current status:

```
curl -s -i -X POST http://localhost:8080/api/events -H "Content-Type: application/json" --data-binary "@examples/payment.json"
```

```
HTTP/1.1 202
Location: /api/events/evt-1001

{"eventId":"evt-1001","status":"PROCESSED"}
```

Reuse the `eventId` with a different amount:

```
curl -s -i -X POST http://localhost:8080/api/events -H "Content-Type: application/json" --data-binary "@examples/payment-changed.json"
```

```
HTTP/1.1 409

{"detail":"Event evt-1001 already exists with a different payload","instance":"/api/events","status":409,"title":"Conflict"}
```

An invalid request names each failing field:

```
curl -s -i -X POST http://localhost:8080/api/events -H "Content-Type: application/json" --data-binary "@examples/invalid.json"
```

```
HTTP/1.1 400

{"detail":"Invalid request","instance":"/api/events","status":400,"title":"Bad Request","errors":[{"field":"amount","message":"must be greater than 0"},{"field":"currency","message":"must be an ISO 4217 currency code"},{"field":"eventId","message":"must be 1 to 64 letters, digits, '_' or '-'"}]}
```

An amount above the maximum (10000.00 by default) is accepted and then fails in processing:

```
curl -s -X POST http://localhost:8080/api/events -H "Content-Type: application/json" --data-binary "@examples/large-payment.json"
curl -s http://localhost:8080/api/events/evt-1002
```

```
{"eventId":"evt-1002","status":"RECEIVED"}
{"eventId":"evt-1002","customerId":"customer-42","type":"PAYMENT","amount":25000.00,"currency":"ILS","status":"FAILED","timestamp":"2026-11-02T09:00:00Z","createdAt":"2026-11-21T20:56:37.719Z","processedAt":null,"failureReason":"amount 25000.00 exceeds the maximum of 10000.00"}
```

An unknown event:

```
curl -s -i http://localhost:8080/api/events/evt-unknown
```

```
HTTP/1.1 404

{"detail":"Event evt-unknown not found","instance":"/api/events/evt-unknown","status":404,"title":"Not Found"}
```

A customer's events, filtered by `timestamp` range (inclusive) and status, newest first. All query parameters are optional; `size` defaults to 20:

```
curl -s "http://localhost:8080/api/customers/customer-42/events?from=2026-11-01T00:00:00Z&to=2026-11-30T23:59:59Z&status=PROCESSED&page=0&size=10"
```

```
{"content":[{"eventId":"evt-1001","customerId":"customer-42","type":"PAYMENT","amount":1250.50,"currency":"ILS","status":"PROCESSED","timestamp":"2026-11-01T10:15:00Z","createdAt":"2026-11-21T20:56:34.285Z","processedAt":"2026-11-21T20:56:34.379Z","failureReason":null}],"page":0,"size":10,"totalElements":1,"totalPages":1}
```

A page larger than 100, or `from` after `to`, is rejected:

```
curl -s -i "http://localhost:8080/api/customers/customer-42/events?size=500"
```

```
HTTP/1.1 400

{"detail":"Invalid request","instance":"/api/customers/customer-42/events","status":400,"title":"Bad Request","errors":[{"field":"size","message":"must be at most 100"}]}
```

## Event lifecycle

An event is stored as `RECEIVED` and its `eventId` is put on the queue. A worker claims it (`PROCESSING`) on a 60-second lease and stores `PROCESSED`, or `FAILED` with a reason. A transient error leaves the message on the queue for another attempt; after 3 attempts the event is `FAILED` with "max attempts exceeded". See [Event lifecycle](ARCHITECTURE.md#event-lifecycle).

## Key decisions

- MongoDB is the source of truth and the queue carries only the `eventId`, so a lost or duplicated message can delay an event but never corrupt it. [Overview](ARCHITECTURE.md#overview)
- `_id` is the `eventId`: duplicate and concurrent `POST`s are decided by the unique index, never by a lookup first. [Idempotency and race conditions](ARCHITECTURE.md#idempotency-and-race-conditions)
- A worker claims an event with one atomic `findAndModify` and a lease, and completes it only with its own claim token. [Processing flow](ARCHITECTURE.md#processing-flow)
- SQS redelivery is the retry; MongoDB counts attempts and decides when an event is `FAILED`; a sweeper re-publishes events whose message was never sent or got lost. [Failure handling and retries](ARCHITECTURE.md#failure-handling-and-retries)
- One compound index serves the customer query with and without `status`; a partial index keeps the sweeper's scan small. [Data model and indexes](ARCHITECTURE.md#data-model-and-indexes)
- SQS Standard with ElasticMQ locally, on Spring Boot 4.1 and Spring Cloud AWS 4.1. [Decisions and trade-offs](ARCHITECTURE.md#decisions-and-trade-offs)

## Configuration

Every setting comes from an environment variable with a local default. [`.env.example`](.env.example) lists them. Docker Compose reads a `.env` file if you copy it there; on the host, set the variables in the shell.
