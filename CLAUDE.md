# event-processing-service

A Spring Boot service that accepts business events over REST, stores them in MongoDB and processes them asynchronously through an SQS queue. This is a home assignment with a 4 to 6 hour estimate: build a focused solution, not an over-engineered one.

`ARCHITECTURE.md` is the design and the single source of truth: API contract, data model, indexes, flows, failure handling and decisions. Build what it says. If the code would need to differ from it, stop and ask.

## Stack

- Java 21, Maven wrapper.
- Spring Boot 4.1.x (generated with start.spring.io) with the Spring Cloud AWS 4.1.x release documented as compatible with it. Not 4.2 until Spring Cloud AWS supports it. Boot 4 uses Jackson 3 by default; check whether that Spring Cloud AWS release needs the Jackson 2 bridge and add it if so. If SQS integration isn't working within 30 minutes in phase 1, stop and ask.
- Spring Data MongoDB, Bean Validation, Actuator.
- ElasticMQ (`softwaremill/elasticmq-native`, pinned) as local SQS. Not LocalStack: current images need an auth token.
- JUnit 5, Mockito, Testcontainers (MongoDB, ElasticMQ), Awaitility, JaCoCo.

## Commands

- `./mvnw verify` (Windows: `mvnw.cmd verify`): build, all tests, coverage check (80% lines). Docker must be running.
- `docker compose up --build`: MongoDB, ElasticMQ and the app on http://localhost:8080.
- `docker compose up -d mongo elasticmq`, then in PowerShell `$env:AWS_REGION="us-east-1"; $env:AWS_ACCESS_KEY_ID="local"; $env:AWS_SECRET_ACCESS_KEY="local"; $env:AWS_ENDPOINT_URL_SQS="http://localhost:9324"` and `.\mvnw.cmd spring-boot:run`: app on the host, dependencies in Docker.

## Code rules

1. Keep it simple. Comments only for the reason behind a non-obvious decision. Catch an exception only where you handle it specifically.
2. Packages: `api`, `application`, `domain`, `persistence`, `messaging`, `recovery`, `config`. `domain` depends on nothing in the app.
3. MongoDB only through `EventRepository` (reads, insert) and `EventStateStore` (atomic transitions). SQS only through `EventPublisher`, `EventMessageListener` and `QueueSettingsCheck` (startup check of queue settings).
4. Every correctness-critical state change is one atomic, conditional MongoDB operation. Never read-then-write. Duplicates are detected by `DuplicateKeyException`, never check-then-insert.
5. Acknowledge a message only after the final state is stored, or when no work is needed.
6. Money is `BigDecimal`. Times are `Instant` from an injected `Clock`. Statuses are the enum. Limits, timeouts and queue names live in one `@ConfigurationProperties` record.
7. Framework first: Bean Validation, `@RestControllerAdvice` with `ProblemDetail`, Spring Data paging, `SqsTemplate` and `@SqsListener`, `@Scheduled`, Actuator, Spring Boot structured logging, `@ServiceConnection`, `@MockitoBean`.
8. DTOs are records, separate from the domain `Event`. The response fields are exactly those in `ARCHITECTURE.md`. Controllers hold no logic. Errors never expose stack traces.
9. Log state transitions at INFO with `eventId` in the MDC. Never log payloads or secrets.
10. No secrets in the repo. Config from environment variables with safe local defaults, listed in `.env.example`.

## Tests

- Unit tests (Mockito) for decisions inside a class.
- Concurrency, atomicity, queue and recovery behaviour is tested on real MongoDB and ElasticMQ (Testcontainers), never only with mocks.
- Awaitility for async assertions, never `Thread.sleep`. Test names describe behaviour.
- Never lower the coverage threshold, disable a test or exclude a file to get green.

## Branches and workflow

`main` always builds. Each phase below is one short-lived branch off `main`, with several small commits. No `develop` or release branches.

For each phase:
1. Create the phase's branch from an up-to-date `main`.
2. Build it with tests, in small commits that each leave `./mvnw verify` green.
3. Verify every criterion yourself: run the tests, run the commands, call the API.
4. Have a fresh subagent that did not write the code review the branch diff against these rules and `ARCHITECTURE.md`, as a strict senior engineer deciding whether to merge. Fix what it would block.
5. Tick the phase's boxes here, commit, and stop. I push the branch, open the PR, review and merge it myself.

Never push, never merge to `main`, never use `--no-verify`. When an AI suggestion turns out wrong or I reject it, add a row to the log in `AI_USAGE.md`.

## Phases

### 1. `feature/project-setup`
Project with the stack above, config properties record, `elasticmq.conf` (`events-queue` with 90 s visibility and DLQ `events-dlq` at `maxReceiveCount = 5`), `docker-compose.yml` with pinned images and health checks, multi-stage non-root `Dockerfile`, `.env.example`, Testcontainers base class, JSON logging.
- [x] `./mvnw verify` green with a context test against the containers
- [x] `docker compose up --build`: `/actuator/health` is `UP` and both queues exist

### 2. `feature/events-api`
`Event`, enums, repository, `IndexInitializer` (the indexes in `ARCHITECTURE.md`), DTOs with validation, `EventService.create` with duplicate handling, both GET endpoints, `GlobalExceptionHandler`. `EventPublisher` is an interface with a no-op implementation for now.
- [x] 20 concurrent POSTs with one `eventId`: one document, all `202`, one publish
- [x] Same `eventId` and payload returns `202` with current status; different payload returns `409`
- [x] Indexes exist with the specified keys and partial filter
- [x] Customer query filters by date range and status, pages newest first, rejects `size > 100` and `from > to`

### 3. `feature/async-processing`
`SqsEventPublisher`, `EventStateStore` (`claim`, `complete`, `fail`), `EventProcessor`, `EventProcessingService`, `EventMessageListener`, `eventId` in the MDC.
- [x] POST, then GET eventually shows `PROCESSED` with `processedAt`
- [x] The same message delivered twice concurrently runs the processor once
- [x] Amount above the maximum ends `FAILED` with a reason
- [x] On real MongoDB: a finished event can't be claimed, an expired lease can, a stale `claimToken` can't complete

### 4. `feature/failure-recovery`
Transient exceptions propagate (no ack), `StuckEventSweeper` (fail exhausted, re-publish stuck, batched, can be disabled), publish failure in create is logged and the request still returns `202`.
- [x] Transient failure then success: `PROCESSED` with `attempts = 2`
- [x] Expired `PROCESSING` with no message in the queue: the sweeper gets it to `PROCESSED`
- [x] `RECEIVED` whose publish failed: the sweeper gets it to `PROCESSED`
- [x] Failing `maxAttempts` times: `FAILED` with "max attempts exceeded"
- [x] Unparseable message lands in `events-dlq`

### 5. `docs/final-documentation`
`README.md` (both ways to run, how to test, a curl for every endpoint, key decisions in a few lines linking to `ARCHITECTURE.md`). Update `ARCHITECTURE.md` to match the code. Finish `AI_USAGE.md`. Plain, concise prose, first person for decisions, no filler.
- [ ] Fresh clone: `docker compose up --build` and every README curl works as written
- [ ] Fresh clone: `./mvnw verify` green
- [ ] A fresh reviewer finds no claim in the docs that the code doesn't back
