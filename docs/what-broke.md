# What broke, and how it was fixed

A running log of real problems found while building and testing the platform.
Each entry: symptom, root cause, fix, lesson.

## 1. Kafka lost all events when its container was recreated

- Symptom: after `docker compose down` and `up`, 54 orders stayed PENDING forever.
  Topic end offsets showed only the events produced after the restart.
- Root cause: the `apache/kafka` image stores data in `/tmp` by default. A volume was
  mounted at `/var/lib/kafka/data` but Kafka was never told to use it, so the volume
  stayed empty and the data died with the container. Postgres volumes were correct,
  so the databases kept their history while the broker lost its own.
- Fix: set `KAFKA_LOG_DIRS=/var/lib/kafka/data`. Verified by writing a message,
  destroying and recreating the containers, and reading the message back.
- Lesson: "I mounted a volume" is not the same as "my data is on the volume". Test
  durability by destroying the container, not by trusting configuration.

## 2. Validation errors returned no field details

- Symptom: an invalid `POST /orders` returned 400 with the generic detail
  "Validation failure" and no list of bad fields.
- Root cause: since Spring 6.1, a controller with a constraint directly on a parameter
  (the `Idempotency-Key` header) switches on built-in method validation for all of its
  parameters. Failures then surface as `HandlerMethodValidationException`, not the
  `MethodArgumentNotValidException` the handler covered.
- Fix: handle both exception types and build one `errors` map by field name.
- Lesson: a smoke test that checks response bodies, not just status codes, caught this.

## Open issues (planned experiments)

- Orders can stay PENDING forever if an event is lost or a service dies mid-saga, and
  nothing notices. Planned: a saga timeout sweeper that cancels stale PENDING orders
  and releases their stock. To be reproduced first by killing a service mid-saga
  (Phase 11), then fixed.
- Consumers currently use Spring's default error handling (a few immediate retries,
  then the record is skipped). Planned: exponential backoff plus dead-letter topics
  (Phase 7) so a poison message is parked, never lost.
