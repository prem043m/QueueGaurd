# QueueGuard Phase 4.0 — Observability

## Architecture

QueueGuard services export Micrometer metrics from Spring Boot Actuator. Prometheus scrapes each service's dedicated management port directly; traces use Micrometer Tracing with the OpenTelemetry bridge and are exported over OTLP through the OpenTelemetry Collector to Tempo. Services write structured JSON to stdout, Grafana Alloy reads Docker container logs, and Loki stores them. Grafana provisions Prometheus, Tempo, Loki, and the version-controlled dashboards.

The existing `monitor-service` remains responsible for its service probes, load feedback, and dashboard. It is not replaced by Prometheus or Grafana.

## Service instrumentation inventory

| Service | HTTP | PostgreSQL | Redis | Background processing |
|---|---|---|---|---|
| `api-gateway` | Inbound requests and traced HTTP proxy/rate-limit calls | Gateway audit persistence | None directly | Audit flush |
| `auth-service` | Inbound requests | User database | None | None |
| `queue-service` | Inbound requests | Durable queue messages | Ready queue | Visibility reaper and reconciliation |
| `worker-service` | Health/control requests; traced queue and monitor calls | Job results | None directly | Polling and concurrent processing |
| `rate-limit-service` | Inbound requests | Ban/adaptive state | Token buckets | None |
| `monitor-service` | Inbound requests and traced probes | Health/load history | None directly | Health probes and cleanup |
| `queue-management-service` | Inbound requests | QMS-owned domain/outbox/inbox | Redis Streams | Outbox publisher, consumer, pending recovery |

## Metrics catalog

All services share the `application`, `environment`, and `version` metric tags. These tags are configured from `spring.application.name`, `DEPLOYMENT_ENV`, and `APP_VERSION`. Request IDs, trace IDs, tenant IDs, event IDs, queue names, and exception text are not metric labels.

Spring Boot/Micrometer provide HTTP server request counts, status and latency, JVM, process/system CPU, Hikari connection-pool metrics, and Actuator health. The Prometheus registry exposes `/actuator/prometheus` on the management port.

QueueGuard metrics include:

| Metric | Purpose | Labels |
|---|---|---|
| `queueguard.queue.operations` | Queue enqueue, claim, ack, and nack operation outcomes | Fixed operation and success/failure outcome |
| `queueguard.queue.operation.duration` | Queue operation duration | Fixed operation |
| `queueguard.queue.messages` | Enqueued, duplicate enqueue, claimed, acknowledged, retry-scheduled, and dead-lettered messages | Fixed result |
| `queueguard.queue.pending`, `queueguard.queue.claimed` | Aggregate infrastructure queue workload, refreshed by the existing queue-list operation | None |
| `queueguard.queue.state.snapshot.age.seconds` | Age of the last aggregate queue snapshot | None |
| `queueguard.outbox.events` | Outbox claim/publication/retry outcomes | Fixed outcome |
| `queueguard.outbox.publish.duration` | Per-event publish attempt duration | None |
| `queueguard.outbox.pending` | PENDING and PROCESSING outbox records | None |
| `queueguard.outbox.oldest.pending.age.seconds` | Age of the oldest PENDING outbox event | None |
| `queueguard.inbox.events` | Received, processed, duplicate, failed, retry, exhausted, and dead-letter outcomes | Fixed outcome |
| `queueguard.inbox.retry.attempts` | Persisted inbox retry attempts | None |
| `queueguard.inbox.processing.duration` | Inbox processing duration | None |
| `queueguard.inbox.pending` | RECEIVED, PROCESSING, and RETRYABLE inbox records | None |
| `queueguard.worker.jobs` | Worker job success/failure | Fixed outcome |
| `queueguard.worker.job.processing.duration` | Worker job duration | None |
| `queueguard.worker.jobs.in.flight` | Current worker in-flight jobs | None |

QMS pending gauges are refreshed every 30 seconds from status-indexed repository counts. Infrastructure queue aggregate gauges are snapshots derived from the existing `/queues` listing operation; their freshness is exposed by the snapshot-age metric. The current Redis client integration does not provide verified command-level Micrometer metrics, so Redis command rates/errors are intentionally not fabricated.

## Trace propagation

Micrometer Tracing with the OpenTelemetry bridge records inbound HTTP spans. The shared JDK HTTP client wrapper creates outbound spans and injects the configured W3C trace context. It is used by gateway proxy/rate-limit calls, worker queue/monitor calls, and monitor probes/load feedback.

JDBC tracing is enabled via datasource-micrometer with automated query-text redaction (`jdbcStatementRedactionFilter`), ensuring SQL statements, parameters, and `jdbc.query[*]` attributes are stripped while preserving database client span hierarchy, execution timing, and `peer.service` tagging.

Lettuce Redis client tracing is configured via `ClientResourcesBuilderCustomizer` with `MicrometerTracing`, producing client spans (e.g. `xreadgroup`, `zpopmin`) attached to active parent observation contexts without exposing sensitive keys or values.

The QMS transactional outbox captures incoming W3C trace context (`traceparent` and `tracestate`) into `outbox_events` (migration V9). `RedisStreamConsumer` propagates this context from Redis Streams, creating correlated `outbox.consume` child spans linked directly to the producing HTTP trace.

## Structured logs and correlation

The shared Logback configuration emits JSON to stdout with timestamp, level, logger, thread, message, service name, environment, version, and MDC values. The shared servlet filter places the existing `X-Request-Id` (or a generated value) in `correlationId` and removes it in a `finally` block. Micrometer tracing contributes `traceId` and `spanId` when a span is active. Alloy uses Docker Compose service/project labels and a fixed `environment=local` label; trace IDs remain JSON fields, not Loki labels.

Do not add authorization headers, tokens, credentials, candidate data, or request payloads to logs. Alloy requires access to the Docker socket for container discovery/log collection; only enable the local observability profile in a trusted development environment.

## Local Compose

The existing application-only workflow is unchanged:

```powershell
docker compose up --build
```

Start the application and observability stack together with:

```powershell
docker compose --profile observability up --build
```

The observability services use a Compose profile so the original stack remains available without the additional telemetry containers. Ports exposed on the host are loopback-only:

- Grafana: `http://localhost:3000` (anonymous Viewer for local exploration)
- Prometheus: `http://localhost:9090`

Application management endpoints listen on port 9090 inside the Compose network and are not published to the host. Each application's Prometheus path is `/actuator/prometheus`. Grafana provisions the Prometheus, Loki, and Tempo data sources automatically; logs and traces are explored through Grafana.

Settings accepted by the applications include `APP_VERSION`, `DEPLOYMENT_ENV`, `MANAGEMENT_SERVER_PORT`, `TRACING_SAMPLING_PROBABILITY`, and `OTEL_EXPORTER_OTLP_ENDPOINT`. The Compose environment sets local defaults; non-Compose local runs default to an OTLP Collector on `localhost:4318`.

Tempo is pinned to 2.10.2 because the checked-in local-storage configuration uses the Tempo 2.x `ingester` and `compactor` blocks, which Tempo 3 removed. Grafana mounts only the datasource and dashboard provisioning directories so the image's optional provisioning directories remain available. Loki's gRPC server send/receive limits are 16 MiB to support the observed 8.5 MiB, 1,000-line local log query.

Prometheus retains 15 days; Tempo and Loki are configured for 168 hours. Named Compose volumes persist Prometheus, Grafana, Tempo, Loki, and Alloy data. `docker compose down` stops and removes the containers without deleting those volumes. `docker compose down -v` deletes telemetry and application data and should only be used when intentionally resetting the local environment.

## Dashboards

1. **QueueGuard Service Overview** — scrape health, request volume, p95 latency, and 5xx rate.
2. **QueueGuard Application Performance** — throughput, latency percentiles, status distribution, heap, CPU, and GC pauses.
3. **QueueGuard Database and Redis** — Hikari pool utilization and a note describing the uninstrumented Redis command metrics.
4. **QueueGuard Queue and Event Processing** — queue outcomes/workload, outbox/inbox outcomes, retries, pending counts, and oldest outbox age.
5. **QueueGuard Background Processing** — worker throughput/failures/in-flight workload and consumer processing duration.

Dashboards query only implemented application series; missing data before requests are made is expected.

## Verification and limitations

Verification performed on 2026-11-26:

```powershell
mvn --batch-mode --no-transfer-progress clean verify
```

The full Maven reactor succeeded: 179 tests, 0 failures, 0 errors, and 0 skipped.

Runtime integration verification:

- Grafana 13.2.0 is healthy. The login form renders with login enabled; anonymous access is Viewer-only, and the user confirmed the existing administrator login. The `queueguard_grafanadata` volume was preserved.
- Grafana provisions all three stable datasource UIDs. Prometheus, Loki, and Tempo queries through Grafana returned HTTP 200, including real application traces from Tempo.
- All seven QueueGuard Prometheus targets were UP. Representative process, JVM, HTTP, database-pool, and queue metrics returned real series.
- Tempo 2.10.2 returned ready. Verified PostgreSQL child spans with SQL statement redaction, Lettuce Redis spans with parent context propagation and no exposed keys/data, and QMS outbox-to-Redis-Stream-to-consumer spans sharing the trace context.
- Loki returned ready. Structured JSON logs containing `traceId`, `spanId`, and `correlationId` were ingested by Alloy and verified correlated with Tempo traces.
- All five provisioned dashboards returned HTTP 200. Every dashboard PromQL expression returned a successful query response; the Service Overview rendered live series in Grafana.
- `docker compose --profile observability config --quiet` and `git diff --check` passed.

Known limitations to track:

- Redis command-level metrics (counters/timers) are not currently emitted via micrometer; command tracing via Lettuce spans is verified.
- Queue workload gauges reflect the most recent existing queue-list operation; check the snapshot-age series when interpreting them.
- Grafana anonymous access is intentionally Viewer-only and the published UI is bound to localhost. Do not expose this Compose configuration to an untrusted network.
