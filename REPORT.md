# QueueGuard: An Intelligent and Fault-Tolerant Distributed Queue Management System with Concurrency Control and Adaptive Rate Limiting

**Project Report**

---

## Abstract

Modern cloud applications must process huge volumes of background work — notifications, payments, media processing, order fulfilment — across thousands of concurrent users, while staying available under infrastructure faults and deliberately hostile traffic. This project, **QueueGuard**, addresses both problems at once. It is a distributed queue management platform built entirely on a **microservices architecture** (Java 17, Spring Boot 3, PostgreSQL, Redis, Kubernetes) in which six independently deployable services — *API Gateway*, *Auth*, *Queue*, *Worker*, *Rate-Limit* and *Monitor* — cooperate to provide durable priority-based message queuing, strictly bounded worker concurrency, and an **adaptive, IP-aware rate limiting subsystem** that throttles and bans abusive clients within seconds while legitimate multi-user traffic continues to be served.

Every service owns a private database (database-per-service pattern), so a failure or an attack against one component can never corrupt another. Messages are claimed atomically (Redis `ZPOPMIN` + guarded SQL updates), processed under semaphore-bounded concurrency, retried with exponential backoff, and dead-lettered after exhaustion. A monitor service computes a live load score and feeds it back into the rate limiter, which scales every client IP's allowance up to 1.5× when idle and down to 0.2× under pressure. Evaluation with a simulated single-IP flood shows that 100% of abusive requests are throttled (HTTP 429) or banned, while concurrent multi-user workloads complete unaffected. The system is packaged with Docker Composes for local use and production-style Kubernetes manifests (probes, HPA, PodDisruptionBudgets, NetworkPolicies, Secrets) for deployment on Minikube or any cluster.

**Keywords:** distributed queue, microservices, rate limiting, token bucket, concurrency control, fault tolerance, dead-letter queue, Kubernetes, Spring Boot, Redis.

---

## 1. Problem Statement

Background job processing in multi-user web applications is deceptively hard:

1. **Volume & concurrency.** Many users submit jobs simultaneously; the system must accept, order, and process them without double-processing or starvation.
2. **Faults.** Worker pods crash, networks partition, databases restart. No message may be lost, and stuck work must be reclaimed automatically.
3. **Hostile traffic.** A single client IP may hammer the API (credential stuffing, resource exhaustion, denial-of-service). The system must detect this per-IP, throttle it with a *fair* limit, escalate to temporary bans, and — critically — keep serving everyone else while under attack.
4. **Operability.** The behaviour must be observable and demonstrable, and the architecture must justify itself academically and practically.

**Objective:** build *QueueGuard* — an intelligent, fault-tolerant distributed queue management system with concurrency control and adaptive rate limiting — as a complete microservices project: code, per-service databases, containers, Kubernetes deployment, security hardening, load/attack evaluation scripts, and documentation.

---

## 2. Functional & Non-Functional Requirements

### Functional
- **F1** User registration/login with JWT authentication (multiple concurrent users).
- **F2** Create named queues with configurable max concurrency and max attempts.
- **F3** Enqueue messages with payload, priority (0–9), optional delay, and idempotency keys.
- **F4** Workers claim message batches atomically; ack on success, nack with retry/backoff on failure.
- **F5** Automatic visibility-timeout recovery (crashed workers) and dead-letter queue (DLQ) with requeue.
- **F6** Per-IP rate limiting with token buckets, strike counting, exponential IP bans, and admin ban/unban.
- **F7** *Adaptive* limiting: limits shrink automatically under load and relax when idle.
- **F8** Live monitoring: service health, load score, timeline, dashboard.

### Non-Functional
- **N1 Fault tolerance** — no message loss across crashes; self-healing reconciliation.
- **N2 Concurrency safety** — atomic claims; bounded worker parallelism; optimistic locking.
- **N3 Security** — single-IP abuse must be stopped by rate limiting/banning; JWT; hashed passwords; hardened headers; NetworkPolicies; non-root containers; audit trail.
- **N4 Scalability** — stateless services scale horizontally (HPA); workers scale with backlog.
- **N5 Portability** — Docker Compose locally, Kubernetes (Minikube or managed) in deployment.

---

## 3. System Architecture

```
                         +--------------------------------------------- Kubernetes / Docker ----+
   many users            |                                                                       |
   (browsers,  clients)  |   +------------------- api-gateway (8080) -------------------+       |
        |                |   |  request-id · IP extraction · SECURITY HEADERS           |       |
        v                |   |  PER-IP RATE LIMIT CHECK -----> 429 / Retry-After        |       |
   [ LoadBalancer ]----->|   |  JWT verification · CIRCUIT BREAKERS · audit log         |       |
                         |   +----+------------+------------+------------+--------------+       |
                         |        |            |            |            |                     |
                         |        v            v            v            v                     |
                         |  +----------+ +-----------+ +----------+ +-----------+              |
                         |  |  auth    | |  queue    | |  worker  | | rate-limit|   +--------+ |
                         |  | service  | |  service  | | service  | |  service  |   |monitor | |
                         |  | (8081)   | |  (8082)   | |  (8083)  | |  (8084)   |   |(8085)  | |
                         |  +----+-----+ +-----+-----+ +----+-----+ +-----+-----+   +---+----+ |
                         |       |             |  ^          |   ^          |   ^        |   |  |
                         |       |             |  | claim/   |   | check    |   | load   |   |  |
                         |       |             |  | ack/nack |   |          |   +--------+---+  |
                         |       |             |  +----------+   |          |                    |
                         |       v             v                 v          v                    |
                         |  +----------+  +---------+        +----------------------------+     |
                         |  | auth_db  |  | queue_db|        |            Redis           |     |
                         |  | (Postgr.)|  | (Postgr)|        | ready-sets · token buckets |     |
                         |  +----------+  +---------+        +----------------------------+     |
                         |  | worker_db|  | ratelimit_db  |  monitor_db | gateway_db  |         |
                         |  | (Postgr.)|  | (Postgr.)     |  (Postgr.)  | (Postgr.)   |         |
                         |  +----------+  +---------------+-------------+-------------+         |
                         +---------------------------------------------------------------------+
```

**Request flow (typical):** client → *api-gateway* (request-id, client-IP, security headers) → *rate-limit-service* decision (allow / 429 / ban) → JWT validation → circuit-breaker-protected proxy call to the owning microservice → response with `X-RateLimit-*` headers. Producers enqueue via *queue-service*; *worker-service* pods concurrently claim and process; *monitor-service* probes everything, computes a load score and closes the adaptive loop into *rate-limit-service*.

---

## 4. Why Microservices? (vs Monolithic vs Hybrid) — Architecture Justification

### 4.1 The three candidates

| Criterion | **Monolith** (one Spring Boot app) | **Hybrid** (monolith + separate workers) | **Microservices** (chosen) |
|---|---|---|---|
| Build & deploy simplicity | ★★★ Single artifact | ★★ Two artifacts | ★ Six artifacts + infra (tooling mitigates) |
| Fault isolation | ★ One crash takes down auth+queue+API | ★★ Worker isolated; core still single | ★★★ One service failing ≠ system failing |
| Independent scaling | ★ Scale everything together | ★★ Workers scale separately | ★★★ Gateway/queue/workers scale on their own signals |
| Attack containment (our key requirement) | ★ An API flood starves the whole JVM incl. queue internals | ★★ Workers survive; core API still coupled | ★★★ Abuse hits the gateway tier; per-service bulkheads + per-IP limiter absorb it |
| Concurrency isolation | ★ Web threads and job workers share one thread-pool/JVM heap | ★★ Workers separate | ★★★ Worker pools sized/tuned per workload |
| Per-service security policy | ★ One network identity | ★★ Partial | ★★★ NetworkPolicies, Secrets, non-root images, least privilege per service |
| Data ownership | ★ One schema, shared tables | ★ Usually one schema | ★★★ Database-per-service; no cross-service table coupling |
| Independent tech evolution & parallel team work | ★ | ★★ | ★★★ |
| Consistency of transactions | ★★★ Local ACID | ★★ | ★ Needs discipline (idempotency, sagas) |
| Operational complexity | ★ Low | ★★ Medium | ★★★ Higher (needs Kubernetes/orchestration) |
| Network latency & debugging | ★★★ In-process calls | ★★ | ★ Remote calls + distributed tracing needed |

### 4.2 Why QueueGuard specifically needs microservices

1. **Radically different scaling shapes.** The gateway must scale with *request rate* (and attack intensity); workers must scale with *queue depth*; the rate-limiter must stay small and fast. In a monolith, an attack that forces gateway scaling also scales — and risks — the queue engine and auth logic. QueueGuard's HPA scales `api-gateway` (2→6 replicas) and `worker-service` (2→8 replicas) *independently*.
2. **Fault isolation is a functional requirement (N1/N3).** If the worker pool is saturated, or a worker deployment is mid-rollout, the API must keep accepting work. If the queue-service is recovering, authentication and rate limiting must keep protecting the perimeter. Only microservices give these blast-radius guarantees.
3. **Security bulkheads against intentional abuse (the professor-attack scenario).** All hostile traffic lands on the gateway + rate-limit tier. These two are disposable, horizontally scalable, and shield every other service behind NetworkPolicies that *only* admit gateway/monitor traffic. A monolith presents one big process and one big port to the attacker.
4. **Concurrency control needs its own runtime.** Bounded worker parallelism (semaphores), visibility-timeout reclaim and retry backoff are wildly different workloads from request/response APIs — different thread models, different resource profiles, different deployment cadence.
5. **Independent deployment & evolution.** Queue internals (claim protocol, scoring) can change without re-testing auth or the dashboard; the rate-limiting policy can be tuned and redeployed in seconds during an attack — exactly the elasticity the brief demands.
6. **Database-per-service = corruption containment.** A runaway migration or a poisoned write in one database can never cascade.

### 4.3 Why NOT a monolith?

A monolith would be faster to build and gives free ACID transactions — and for a small internal tool it would be the right call. For QueueGuard it fails the brief: (a) one failure domain contradicts fault tolerance; (b) one scaling unit contradicts "handle multiple users *and* survive attack"; (c) per-IP adaptive rate limiting at the perimeter is awkward inside the same JVM as the queue engine; (d) the project explicitly requires microservices and Kubernetes, and the requirement itself is well-founded (see table above).

### 4.4 Why NOT hybrid (monolith + detached workers)?

Hybrid — a single core service plus separate worker pods — is the common pragmatic middle ground, and it *would* isolate job processing. It still couples authentication, queue management and the public API into one deployable: an attack on the API surface endangers the queue's availability, upgrades of any core feature redeploy everything, and the rate-limiter/monitoring loop can't be scaled or patched independently. Given that **security under attack is a first-class requirement**, the extra operational cost of full microservices buys a directly required capability: independently scalable and hardenable perimeter services. Kubernetes (probes, self-healing, HPA, PDBs, NetworkPolicies, Secrets) absorbs most of the operational overhead the monolith would have "for free" — which is exactly why the microservices + Kubernetes pairing is the standard industry answer to this problem.

### 4.5 Trade-offs accepted (honest engineering)

- **Distributed transactions:** avoided by design — enqueue is a single-service write; processing uses idempotency keys and at-least-once delivery with dedup.
- **Network hops:** one extra hop for the rate-limit check per request; mitigated by a 1.5 s timeout and a local fallback bucket, and by keeping all hops in-cluster (<1 ms).
- **Ops complexity:** handled by Docker Compose (dev) and declarative Kubernetes manifests (deployment), plus uniform health endpoints and a central monitor.

---

## 5. Microservice Catalog

| Service | Port | Responsibility | Database | Key endpoints |
|---|---|---|---|---|
| **api-gateway** | 8080 | Single entry point: rate-limit enforcement, JWT auth, routing, circuit breakers, security headers, audit log | `gateway_db` (audit_log) | `/api/**` (proxy), `/health`, `/` (dashboard) |
| **auth-service** | 8081 | Registration, login, JWT issuing, profile | `auth_db` (users) | `POST /auth/register`, `POST /auth/login`, `GET /auth/me` |
| **queue-service** | 8082 | Queue CRUD, enqueue (idempotent), atomic claim, ack/nack, retries, DLQ, healing reapers | `queue_db` (queue_defs, messages) | `POST /queues`, `POST /queues/{n}/messages`, `POST /queues/{n}/claim`, `POST /messages/{id}/ack|nack`, `GET /queues/{n}/stats\|dead` |
| **worker-service** | 8083 | Claims work, processes under semaphore-bounded concurrency, acks/nacks, records results | `worker_db` (job_results) | `GET /workers/stats`, `POST /workers/pause\|resume` |
| **rate-limit-service** | 8084 | Distributed token buckets (Redis Lua), strikes, exponential IP bans, adaptive multiplier | `ratelimit_db` (ip_bans, adaptive_state) | `POST /ratelimit/check`, `POST /ratelimit/load`, `GET /ratelimit/bans`, `POST /ratelimit/ban/{ip}` |
| **monitor-service** | 8085 | Health probing, metrics ingestion, load-score computation, adaptive feedback push, dashboard | `monitor_db` (health_checks, metric_samples, load_snapshots) | `GET /monitor/health-overview\|load\|timeline`, `POST /monitor/metrics` |

Shared library `common`: uniform `ApiResponse` envelope, exception hierarchy + `@RestControllerAdvice`, **JWT (HS256)** implementation, **PBKDF2** password hashing. Each service is an independent Spring Boot fat JAR with its own Dockerfile stage and Kubernetes Deployment.

---

## 6. Database-per-Service Design

Every service owns exactly one PostgreSQL database; nothing else may touch its tables:

| Database | Owner | Tables |
|---|---|---|
| `auth_db` | auth-service | users (email unique, pbkdf2 hash) |
| `queue_db` | queue-service | queue_defs, messages (status, attempts, idempotency_key unique per queue, version for optimistic locking) |
| `worker_db` | worker-service | job_results |
| `ratelimit_db` | rate-limit-service | ip_bans (strikes, banned_until), adaptive_state (multiplier) |
| `monitor_db` | monitor-service | health_checks, metric_samples, load_snapshots |
| `gateway_db` | api-gateway | audit_log (denied/suspicious requests) |

Redis holds only *ephemeral, high-velocity* state: the per-queue ready-sets (sorted sets) and the rate-limit token buckets (hashes updated by an atomic Lua script). PostgreSQL remains the durable source of truth; a background **reconciler** re-populates Redis from `PENDING` rows, so a Redis restart never loses messages.

---

## 7. Distributed Queue Design

### 7.1 Message lifecycle

```
 enqueue ──> PENDING ──claim──> CLAIMED ──ack──> DONE
                ^                  |
                |      nack (attempts < maxAttempts, exponential backoff)
                +------------------+
                |
                +-- nack (attempts exhausted) or visibility timeout (crash) --> DEAD (DLQ) --requeue--> PENDING
```

### 7.2 Priority ordering
The Redis ready-queue is a sorted set keyed `qg:ready:{queue}` with score

```
score = (9 - priority) * 10^13 + readyAtMillis
```

Lower score pops first: **higher priority first**, then **FIFO by readiness time** (which encodes retry backoff and delayed enqueues). `ZPOPMIN` is atomic in Redis, so concurrent claimers never receive the same message.

### 7.3 Claim protocol (dual-layer concurrency safety)
1. Pop up to `n` ids with `ZPOPMIN` (atomic in Redis).
2. For each id run a guarded SQL update:
   `UPDATE messages SET status='CLAIMED' ... WHERE id=? AND status='PENDING' AND next_attempt_at <= now`
   — only one claimer can win (compare-and-set semantics); losers drop the id or requeue it.
3. **Concurrency cap:** claim size is clipped to `maxConcurrency − in-flight` per queue, so a queue can never have more than `maxConcurrency` messages in flight cluster-wide.

### 7.4 Delivery guarantees
- **At-least-once**: visibility timeout (60 s) reclaims messages from crashed workers (the *reaper*); consumers should be idempotent (the API supports `Idempotency-Key` so producers can also deduplicate).
- **Retry with exponential backoff**: 2^attempts seconds (capped 60 s), encoded into the ready-set score.
- **Dead-letter queue**: after `maxAttempts` failures a message becomes `DEAD` with the last error preserved; operators can inspect and requeue it.
- **Self-healing**: a reconciler re-inserts any `PENDING` message missing from Redis.

---

## 8. Concurrency Control

1. **Atomic dequeue** — Redis `ZPOPMIN` + guarded SQL claim (§7.3): no double-processing under parallel workers.
2. **Optimistic locking** — `@Version` column on messages plus status-guarded bulk updates prevent races between reaper, nack, and claim.
3. **Bounded worker parallelism** — a `Semaphore(maxConcurrency)` per worker pod caps in-flight jobs; the poller never claims more than free permits, so bursts cannot exhaust memory or overload downstreams.
4. **Per-queue in-flight ceiling** — `maxConcurrency` enforced at claim time across *all* worker replicas.
5. **Horizontal worker safety** — workers are stateless; any number of pods may poll the same queue (claims stay atomic), which is exactly what the HPA exploits.
6. **Backpressure** — when workers are saturated, messages simply stay `PENDING` (durable in PostgreSQL) instead of being lost.

---

## 9. Adaptive Rate Limiting (the "intelligence")

### 9.1 Token bucket (distributed, atomic)
Each client IP gets a Redis hash bucket checked by a **Lua script executed atomically** inside Redis:

```
tokens = min(capacity, tokens + Δt · refillRate)        # refill over time
if tokens >= cost: tokens -= cost; ALLOW
else: DENY with retryAfter = ceil((cost - tokens) / refillRate)
```

Default base policy: **10 req/s per IP, burst 20** (plus a higher per-user bucket of 30/60 when a JWT is present).

### 9.2 Adaptive multiplier (feedback control)
Every 10 s the monitor computes a load score in [0,1]:

```
load = 0.35·queueDepthNorm + 0.25·workerInFlightNorm + 0.25·errorRate + 0.15·p95LatencyNorm
targetMultiplier = clamp(1.5 − 1.3·load, 0.2, 1.5)
multiplier ← EWMA(α = 0.3) smoothing
effectiveLimit = baseLimit · multiplier          effectiveBurst = burst · multiplier
```

- Idle system → up to **1.5×** the base limit (legitimate users get headroom).
- Attack / high load → down to **0.2×** (the perimeter tightens automatically). During a flood, queue depth and error signals rise within one probe cycle, so limits shrink within ~10 seconds — *the system defends itself*.

### 9.3 Strike ladder & exponential bans
Every denial adds a strike to the IP (strikes decay after 60 s of good behaviour). At 5 strikes the IP is **banned** with exponential backoff: 1, 2, 4, 8 … minutes (capped at 60). While banned, requests are rejected instantly with `Retry-After`. Operators can ban/unban via `POST /DELETE /api/ratelimit/...`. Ban state is persisted in `ratelimit_db`, so it survives restarts.

### 9.4 Fail-safe degradation
If the rate-limit-service itself is down, the gateway switches to a **conservative local fallback limiter** (5 req/s, burst 10) — never fail-open. If Redis is briefly unreachable, the bucket script fails open with a single token while the durable ban list (PostgreSQL) keeps enforcing bans.

---

## 10. Security Measures (designed to survive an intentional attack)

| Threat | QueueGuard defence |
|---|---|
| Single-IP flood / DoS | Per-IP adaptive token bucket at the gateway → 429 + `Retry-After`; strike ladder → exponential IP **ban** (persisted) |
| X-Forwarded-For spoofing to rotate fake IPs | Proxy headers trusted **only** when `TRUST_PROXY_HEADERS=true` (behind a trusted L7 proxy); default `false` → spoofing is ignored (verified by `attack_sim.py` phase 2) |
| Credential stuffing on login | Login is rate-limited per IP before any password check; PBKDF2-HMAC-SHA256 (210k iterations) slows offline cracking |
| Forged/stolen tokens | HS256 JWT with shared secret (K8s Secret), expiry, constant-time signature comparison |
| Mass assignment / oversized bodies | Bean validation (`@Valid`, `@Size`), ingress `proxy-body-size: 256k` |
| Tracing/abuse forensics | Bounded in-memory audit buffer → `gateway_db.audit_log` (attack-proof: DB slowness can't block request threads; overflow is dropped, not queued) |
| Lateral movement inside cluster | **NetworkPolicies**: default-deny ingress; only gateway/monitor may reach services; Redis only from queue/rate-limit; Postgres only from the namespace |
| Container breakout | Non-root images (uid 1001), read-only secrets via env, no privileged pods, resource limits |
| Header-based attacks | Security headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP on `/api` |
| Brute-force ban evasion | Bans persist in PostgreSQL; adaptive multiplier keeps limits tight while under attack; gateway circuit breakers stop cascades |

---

## 11. Fault Tolerance & Resilience

- **Worker crash** → visibility-timeout reaper requeues in-flight messages (attempt counted).
- **Redis restart** → reconciler rebuilds ready-sets from PostgreSQL (`PENDING` is always durable first).
- **PostgreSQL restart** → services retry via HikariCP; Kubernetes liveness probes restart stuck pods.
- **Downstream outage** → per-service circuit breaker in the gateway (5 consecutive failures → open 10 s → half-open probe) returns clean 503s instead of piling up threads.
- **Rate-limit-service outage** → gateway fallback limiter (§9.4).
- **Deployments** → readiness probes keep endpoints out of rotation until warm; PDBs keep ≥1 replica during node drains; HPA replaces failed pods.
- **Duplicate submissions** → producer idempotency keys (`Idempotency-Key` header) deduplicate enqueues.
- **Message ordering anomalies** → retries honour backoff in the score; DLQ preserves failures for replay.

---

## 12. Kubernetes Deployment

Deliverables in `k8s/` (applied in order by `scripts/deploy-minikube.sh`):

| Manifest | Contents |
|---|---|
| `00-namespace.yaml` | namespace `queueguard` |
| `01-secrets.yaml` | `DB_PASSWORD`, `JWT_SECRET` (Secret; replace with Sealed Secrets in production) |
| `02-configmap.yaml` | service URLs, rate-limit policy, worker tuning, monitor targets |
| `03-postgres.yaml` | init ConfigMap (creates the 6 databases), PVC, Deployment (probes), Service |
| `04-redis.yaml` | PVC, Deployment with AOF persistence, Service |
| `10–15-*.yaml` | one Deployment + Service per microservice: probes (`/health`), resources, non-root security context, envFrom config/secret |
| `20-ingress.yaml` | optional ingress-nginx route (`queueguard.local`) |
| `21-hpa-pdb.yaml` | HPA for gateway (2→6) and workers (2→8) at 70% CPU; PDBs (minAvailable 1) |
| `22-network-policies.yaml` | default-deny + least-privilege allowances (§10) |

Images are built by `scripts/build-images.sh` (shared multi-stage `deploy/Dockerfile` with `SERVICE_NAME` build-arg) — for Minikube, `eval $(minikube docker-env)` first so no registry is needed. The gateway Service is `type: LoadBalancer` (`minikube tunnel` gives the public IP; `minikube service api-gateway -n queueguard --url` works too).

---

## 13. Monitoring & Observability

- **monitor-service** probes every service `/health` every 10 s, stores latency/status history (`monitor_db`), ingests worker counters, computes the load score, and pushes it to the rate-limit-service (closing the adaptive loop).
- **Live dashboard** at the gateway root (`http://<gateway>/`): service health pills, load score meter, queue depth, in-flight jobs, error rate, p95 latency, and a load timeline chart (auto-refreshing, zero external assets).
- **Rate-limit visibility**: `X-RateLimit-Limit / -Remaining / -Multiplier / -Mode` headers on every API response.
- **Actuator** endpoints (`/actuator/health`, `/actuator/metrics`) on every service for Kubernetes probes and tooling.
- **Audit log** of every denied request (IP, path, decision) in `gateway_db`.

---

## 14. Testing & Evaluation

### 14.1 Multi-user load test (`scripts/load_test.py`)
N concurrent users register, log in, and enqueue/poll at human cadence. Expected: all requests 2xx; zero 429s; workers drain the queue; dashboard shows healthy load.

### 14.2 Single-IP attack simulation (`scripts/attack_sim.py`)
One IP floods `GET /api/queues` with 40 threads (~hundreds of req/s). Expected timeline:

1. **0–1 s** — burst absorbed by the 20-token burst bucket.
2. **1–5 s** — bucket empty → mass **HTTP 429** with `Retry-After`; strikes accumulate.
3. **5–10 s** — strike threshold (5) reached → **IP banned** (1 min, doubling per relapse).
4. **Throughout** — the adaptive multiplier collapses towards 0.2× as load/queue signals rise, further tightening limits.
5. **Phase 2** — 30 requests with rotating forged `X-Forwarded-For` headers remain 429 (spoofing ignored with `TRUST_PROXY_HEADERS=false`).
6. **Phase 3** — `GET /api/ratelimit/bans` shows the active ban with strike count and reason.

Meanwhile, requests from *other* IPs (the examinator's colleague, the dashboard, the workers' internal traffic) are unaffected — this is the headline result: **bounded damage per offender, unlimited service for everyone else**.

### 14.3 Fault-tolerance drills
- `kubectl delete pod <worker-pod>` mid-job → messages return to `PENDING` after the 60 s visibility timeout and are re-processed.
- `kubectl delete pod <queue-service-pod>` → service returns via Deployment; ready-set healed by the reconciler.
- `docker compose stop rate-limit-service` → gateway switches to `X-RateLimit-Mode: local-fallback` and keeps throttling.

---

## 15. Project Structure & Tech Stack

```
queueguard/
├── pom.xml                     # Maven parent (Java 17, Spring Boot 3.2.5)
├── common/                     # shared: ApiResponse, exceptions, JWT, PBKDF2
├── services/
│   ├── api-gateway/            # 8080 - routing, rate limiting, JWT, audit, breakers
│   ├── auth-service/           # 8081 - users & tokens            -> auth_db
│   ├── queue-service/          # 8082 - distributed queue         -> queue_db + Redis
│   ├── worker-service/         # 8083 - concurrency-controlled consumer -> worker_db
│   ├── rate-limit-service/     # 8084 - adaptive token buckets    -> ratelimit_db + Redis
│   └── monitor-service/        # 8085 - health, load score, dashboard -> monitor_db
├── deploy/                     # shared Dockerfile, db-init SQL
├── docker-compose.yml          # one-command local stack
├── k8s/                        # full Kubernetes manifests
├── scripts/                    # build/deploy/demo/load-test/attack-sim
└── REPORT.md / README.md
```

**Stack:** Java 17 · Spring Boot 3 (Web, Data JPA, Data Redis, Actuator, Validation) · PostgreSQL 16 · Redis 7 (Lua) · Docker (multi-stage, non-root) · Kubernetes (Deployments, Services, HPA, PDB, NetworkPolicy, ConfigMap, Secret) · Python 3 (test tooling only).

---

## 16. Limitations & Future Enhancements

- **Exactly-once delivery** is not provided (at-least-once + idempotency is the standard trade-off). A transactional outbox would tighten this.
- **Redis is a single instance** here; production would use Redis Sentinel/Cluster.
- **JWT secret rotation** and refresh tokens are future work.
- **Custom-metric autoscaling** (queue depth via Prometheus adapter) would scale workers faster than CPU.
- **Distributed tracing** (OpenTelemetry) would complement the monitor for deep debugging.
- **Geo-distributed queues** (per-region ready-sets with global balancer) for multi-region deployments.

---

## 17. Conclusion

QueueGuard delivers a complete, defensible answer to the brief: a **microservices** distributed queue platform in which every service owns its database, every message is processed under strict concurrency control, and every client IP is governed by an **adaptive** rate limiter that throttles floods within seconds, escalates to exponential bans, and — when the system is under pressure — automatically tightens itself while keeping legitimate multi-user traffic flowing. The architecture is justified against monolithic and hybrid alternatives by the project's own requirements: independent scaling under attack, blast-radius isolation, and per-service hardening — with Kubernetes making that architecture operable and demonstrable on a laptop with Minikube. Under simulated attack, the single offender is stopped at the perimeter; the queue keeps flowing; the dashboard keeps breathing. That is exactly the behaviour an intelligent, fault-tolerant queue management system should exhibit.
