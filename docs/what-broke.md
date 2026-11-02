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

## 3. Dead-letter publishing failed, and a poison message looped forever

- Symptom: a message that exhausted its retries was retried again from the start, over
  and over, and the dead-letter topics stayed empty. The partition was blocked.
- Root cause: Spring Kafka 3.x publishes to `<topic>-dlt` by default. Our topics are
  named `<topic>.DLT` and topic auto-creation is off, so the publish timed out
  ("Topic payment.events-dlt not present in metadata"). A failed recovery makes the
  container redeliver the record, which restarted the retry cycle.
- Fix: the shared error handler now states the destination explicitly
  (`<topic>.DLT`, same partition) instead of relying on the framework default.
- Lesson: failure-handling code needs its own failure tests (scripts/poison-test.sh
  found this). Never rely on a framework default for a name other systems depend on.
  Note the behaviour is deliberate: if the dead-letter publish fails the message is
  retried, never dropped, but that blocks the partition, so a failing recoverer needs
  an alert (Phase 9).

## 4. Integration tests passed alone but failed together ("relation outbox does not exist")

- Symptom: a Testcontainers test class passed; a second class using the same base class
  failed every test because the schema was missing.
- Root cause: Spring caches the application context across test classes, but the container
  was declared per class (`@Container` on a static field), so the extension stopped and
  restarted it between classes. The cached context's Flyway migrations had run against a
  container that no longer existed.
- Fix: singleton container pattern. Start one container per JVM in a static initializer and
  pass its connection details through `@DynamicPropertySource`.
- Lesson: shared (cached) Spring context and per-class containers do not mix. Decide the
  lifecycle of expensive test fixtures deliberately.

## Open issues (planned experiments)

- Orders can stay PENDING forever if an event is lost or a service dies mid-saga, and
  nothing notices. Planned: a saga timeout sweeper that cancels stale PENDING orders
  and releases their stock. To be reproduced first by killing a service mid-saga
  (Phase 11), then fixed.
- Nothing reads the dead-letter topics yet. Planned: a metric and alert on DLT depth
  (Phase 9) and a small replay tool.
