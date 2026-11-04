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

## 5. Good-looking tests and 80% coverage still hid untested wiring

- Symptom: all tests passed, but the first JaCoCo report showed the shared messaging library
  with no coverage row at all, and the Kafka listeners at 23-50% lines and 0% branches.
- Root cause: the integration tests switched the listeners off and called the service classes
  directly, so the path "Kafka record -> parse -> handler" (and the shared retry and
  dead-letter handler) was never run in CI. The shared library had no tests of its own and was
  only exercised indirectly, so its coverage was not even measured.
- Fix: direct tests for the library (transaction rules, SKIP LOCKED, publisher with a real and
  an unreachable broker), end-to-end listener tests against a real Kafka broker (including a
  poison message directly in front of a valid one), and an enforced 80% gate on core logic.
- Lesson: a passing suite measures what it runs, not what matters. Read the coverage report
  per area, look for rows that are missing, and ask what the tests switch off.

## 6. The coverage gate failed on stale data

- Symptom: `./mvnw install -DskipTests` failed with "Coverage checks have not been met" in a
  module whose code and tests were fine.
- Root cause: skipping tests skips running them, but the JaCoCo check still ran. It read the
  coverage data file left by an earlier full build and compared it with the newly compiled
  classes, so every changed class counted as uncovered.
- Fix: when skipping tests, also pass `-Djacoco.skip=true`. CI always builds from a clean
  checkout and runs the full `verify`, so it never sees stale data.
- Lesson: a quality gate must judge the current code. If its input can be stale, it will
  eventually fail (or pass) for the wrong reason.

## 7. Traces fell apart at the outbox: one order, five traces

- Symptom: after adding tracing, one order produced five unrelated traces, one per saga hop, and
  the trace column in the logs changed on every line.
- Root cause: the HTTP request wrote the outbox row and ended. The event was published later by the
  scheduler thread, where no trace was active, so the Kafka producer started a new trace at every hop.
- Fix: store the traceparent in the outbox row and restore it as the parent of an `outbox publish`
  span when sending (ADR-0004). Result: one trace, four services, 21 spans.
- Lesson: asynchronous hand-offs (outboxes, queues, schedulers) are where context gets lost.
  Check the trace of a real request, not just whether tracing is switched on.

## 8. Kafka did not come back after Docker Desktop restarted

- Symptom: after Docker Desktop quit and was restarted, all services logged thousands of
  "could not be established" errors for localhost:9092 and no order progressed past creation.
- Root cause: the Postgres and Redis containers had `restart: unless-stopped`; the Kafka
  container had no restart policy, so it stayed stopped.
- Fix: add `restart: unless-stopped` to Kafka. Orders created during the outage were not lost:
  they waited in the outbox and completed within half a second of Kafka returning (the trace shows
  the 2m22s gap).
- Lesson: check restart policies for every stateful dependency. The outage also gave real evidence
  that the outbox keeps its promise during a broker failure.

## 9. A flaky test: the gauge read NaN

- Symptom: a test of the outbox backlog gauge passed on several runs, then failed with
  "expected 2.0 but was NaN".
- Root cause: Micrometer gauges hold only a weak reference to the object they read from. The test
  created that object inline and kept no other reference, so a garbage collection during the test
  made the gauge report NaN. In production the object is a Spring bean, which the context holds
  strongly, so the real gauges were never affected.
- Fix: keep the object in a field for the duration of the test.
- Lesson: a failure that depends on timing is not "just flaky": find the mechanism (here, garbage
  collection) instead of rerunning until it is green. A gauge that reads NaN means its source object
  is gone.

## Open issues (planned experiments)

- Orders can stay PENDING forever if an event is lost or a service dies mid-saga, and
  nothing notices. Planned: a saga timeout sweeper that cancels stale PENDING orders
  and releases their stock. To be reproduced first by killing a service mid-saga
  (Phase 11), then fixed.
- Nothing reads the dead-letter topics yet. Planned: a metric and alert on DLT depth
  (Phase 9) and a small replay tool.

## Load testing (Phase 10)
- **Docker Desktop quit during long runs (four times).** The VM was allowed all 16 GB of the Mac's RAM, and Jaeger keeps every trace in memory at 100% sampling. Fix: stop Jaeger and set sampling to 0 for load tests, cap Docker at 8-10 GB, run long experiments detached (`nohup caffeinate`) and append results per step so a crash only loses the unfinished step.
- **The Kafka client lag metric (`records-lag-max`) read 0.0 while the broker showed 487 records behind.** It measures the fetch position, not processing. Use broker-side consumer group lag, or the age of an event when it is processed.
- **A measurement that changed nothing is still a result.** The first "after" run accidentally ran on the old thread count. It proved that more partitions alone do not help: the limit was threads per listener.
- **Closing the terminal killed the experiment.** The sweep script now writes each step's row as soon as it finishes, and the resume only re-runs the unfinished steps.

## Failure experiments (Phase 11)
- **My own stop script killed Docker.** `lsof -ti :PORT | xargs kill` matches every process with a connection on the port, and Prometheus (in a container) reaches the services through Docker Desktop's backend, so Docker's backend was killed together with the service. It looked like random Docker crashes for days (five occurrences) and I first blamed memory. The Docker log said `monitor exited: signal: killed` at exactly the second the script ran. Fix: `lsof -ti tcp:PORT -sTCP:LISTEN` (listener only). Lesson: when a failure is deterministic at one moment, find what runs at that moment before tuning resources.
- **A setting I thought was permanent was not.** The 50 ms outbox poll lived in a Java `@Scheduled` default and in one service's yml, and my first patch searched only for an environment-variable name. A control run that did not match the earlier measurement caught it. Lesson: re-measure after making a change permanent.
- **zsh traps in the helper commands.** An unquoted `--include=*.yml` aborted a command ("no matches found"), and an unquoted variable holding several flags is not split by zsh, so a grep silently matched nothing. Quote globs; do not store flags in a string variable.
- **An unwarmed measurement is invalid.** The first control run after a restart sent 314 of 600 orders with a 12.8 s outbox lag. Every sweep warms up for 30 s first.
- **Testcontainers could not find Docker** after Docker Desktop restarted (no `/var/run/docker.sock`). Fix: `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
