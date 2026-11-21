# AI usage

> Draft. The implementation sections are finished at the end; the log grows as I work.

## Tools

- Claude (chat and voice), to review my first design, argue through the trade-offs, and draft `ARCHITECTURE.md` and `CLAUDE.md`, which I then edited.
- Claude Code, for the implementation, working from `CLAUDE.md`.

## How I worked with it

I fixed the design before any code was written and wrote it down in `ARCHITECTURE.md`. `CLAUDE.md` gives Claude Code the code rules and five phases, each on its own branch with criteria I can check. Claude Code builds a phase and stops; a separate agent that didn't write the code reviews the diff; then I review the PR myself and merge it. I kept the push and the merge for myself on purpose.

<!-- End: which parts Claude Code wrote, which I changed, where I stepped in. -->

## How I verified the output

<!-- End: the concurrency and recovery integration tests as the evidence, the clean-clone check, reading every PR diff. -->

## Where I accepted, rejected or corrected the AI

| # | What the AI suggested | What happened | Why |
| --- | --- | --- | --- |
| 1 | My first AI-generated design summary listed "MongoDB (Mongoose)" | Rejected | Mongoose is a Node.js library. The stack is Java, so Spring Data MongoDB |
| 2 | That same summary treated a DLQ redrive policy as the whole failure strategy | Extended | A dead-lettered message left the event stuck in `PROCESSING`, so `GET` would show the wrong status. Now MongoDB decides when an event is `FAILED`, and a sweeper handles stuck events |
| 3 | Claude pointed out that my plan of Mockito-only tests couldn't prove the concurrency guarantees | Accepted | A mock can't show that a unique index or `findAndModify` prevents a race. The concurrency tests run on real MongoDB |
| 4 | Claude's plan used four indexes, two of them for the customer query | I pushed back | I asked why not a single index on customer, status and timestamp. That order would have broken range and sort when no status is sent, but the question led to one index ordered `customerId, occurredAt, _id, status` that covers both cases. Two indexes became one |
| 5 | I wanted Kafka instead of SQS | I accepted the pushback | The assignment asks for SQS explicitly, and for a work queue SQS fits better. Kafka on MSK is documented as the path if consumers ever need replay |
| 6 | Claude's plan used LocalStack for local SQS | Replaced with ElasticMQ | When checked, the current LocalStack image turned out to need an account and auth token since March 2026, so `docker compose up` would fail for a reviewer |
| 7 | Claude recommended Spring Boot 3.5 as the safer choice | Rejected when checked | Spring Boot 3.5 reached end of open-source support in June 2026. A production-oriented service shouldn't start on it, so Spring Boot 4.x |
| 8 | Claude's first plan was about 8 hours, including custom metrics | Cut | The assignment estimates 4 to 6 hours. I kept logs with `eventId` and health checks, and listed the metrics as production work |
| 9 | Claude's `CLAUDE.md` draft asked for "the Spring Cloud AWS 4.1.x release documented as compatible with" Spring Boot 4.1 | Corrected when checked | No release documents Boot 4.1: the compatibility table lists 4.x with Boot 4.0.x, and 4.1.1 is built against Boot 4.0.8. I use 4.1.1, the latest 4.1.x. On Boot 4.1.3, phase 1 tests show the SQS client reaches the queues, and phase 3 tests send with `SqsTemplate` and receive with `@SqsListener` with Jackson 3 converting the body both ways, so I didn't need the Boot 4.0.x fallback. It needs no Jackson 2 bridge |
| 10 | Claude Code's phase 1 config put local AWS credentials and a localhost SQS endpoint in `application.yaml` as defaults | Caught by the review agent, fixed | Static credentials override the default chain, so in AWS they would have replaced the ECS task IAM role. The base config now sets none; compose, the Testcontainers config and `.env.example` supply the local values |
| 11 | After review, Claude Code documented the timing rules (lease below the visibility timeout, `maxAttempts` below `maxReceiveCount`) as a note in `.env.example` | I replaced it with a startup check | A note doesn't stop a wrong value. The app now reads the queue's visibility timeout and redrive policy at startup and refuses to start if either rule is broken |
| 12 | Claude Code's phase 2 explain tests built their own sort document instead of using the repository's query | Caught by the review agent, fixed | Changing the repository's sort would have left the "no in-memory SORT" tests green. They now explain the exact query the repository sends, and I checked that they fail when the sort is broken |
| 13 | Claude Code hashed the request timestamp at full precision but stored it in MongoDB at millisecond precision | Caught by the review agent, fixed | The stored document disagreed with its own hash and `GET` returned a different `timestamp` from the one sent. The timestamp is now truncated to milliseconds before hashing and storing |
| 14 | Claude Code's phase 3 logged a permanent failure as "Event failed: <reason>", and the reason quotes the amount | Caught by the review agent, fixed | The amount is a payload field and the rules say never log payloads. The log line now says only that the event failed; the reason stays on the document and in `GET` |
| 15 | In phase 3, Claude Code committed `66636e5` while `./mvnw verify` was red | Caught by Claude Code, process changed | To check that the concurrency test can fail, it broke `claim` and then restored the file from a copy. The copy kept its old timestamp, so Maven's incremental build reused the broken compiled class. Its commit step only checked that the script ran, not Maven's exit code. The committed code was correct, and a clean `verify` on that commit was green. Since then it runs `clean verify` before every commit, and a failed build stops the commit |
