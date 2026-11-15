# 🛡️ QueueGuard

**An Intelligent and Fault-Tolerant Distributed Queue Management System with Concurrency Control and Adaptive Rate Limiting**

Six Spring Boot microservices + PostgreSQL + Redis, deployable to Kubernetes. Built to handle many concurrent users — and to survive a deliberate single-IP attack.

> 📄 **See [REPORT.md](REPORT.md)** for the full project report: abstract, architecture, *why microservices instead of monolithic/hybrid*, rate-limiting algorithm, security analysis and evaluation. (A Word version `REPORT.docx` is generated next to it.)

---

## Architecture at a glance

```
clients ──► api-gateway ──┬──► auth-service      (auth_db)
              │           ├──► queue-service     (queue_db + Redis)
   rate limit │           ├──► worker-service    (worker_db)
   JWT · CB   │           ├──► rate-limit-service(ratelimit_db + Redis)
   audit      │           └──► monitor-service   (monitor_db)
              └── monitor closes the adaptive loop ──► rate-limit-service
```

| Service | Port | What it does |
|---|---|---|
| api-gateway | 8080 | Single entry: per-IP rate limiting, JWT, circuit breakers, audit log, dashboard |
| auth-service | 8081 | Register/login, JWT, PBKDF2 passwords |
| queue-service | 8082 | Priority queues, atomic claims, retries, DLQ, healing reapers |
| worker-service | 8083 | Semaphore-bounded consumers, crash-safe ack/nack |
| rate-limit-service | 8084 | Redis-Lua token buckets, strikes, exponential IP bans, adaptive limits |
| monitor-service | 8085 | Health + load score + live dashboard (open gateway URL in a browser) |

Every service has **its own PostgreSQL database** (database-per-service). Redis holds the fast paths: ready-sets and token buckets.

---

## Quick start (Docker Compose)

```bash
docker compose up --build
# gateway: http://localhost:8080   (dashboard at /)
./scripts/demo.sh http://localhost:8080       # functional walkthrough
python3 scripts/load_test.py --users 12       # multi-user demo
python3 scripts/attack_sim.py                 # single-IP attack -> throttled & banned
```

## Deploy on Minikube (Kubernetes)

```bash
minikube start --memory=4096 --cpus=4
eval $(minikube docker-env)
./scripts/build-images.sh          # builds all 6 images into minikube's docker
./scripts/deploy-minikube.sh       # applies everything in k8s/
minikube service api-gateway -n queueguard --url    # or: minikube tunnel
```

Then run the same demo/attack scripts against the printed URL. Optional: `minikube addons enable ingress` and apply `k8s/20-ingress.yaml` for `http://queueguard.local`.

---

## API in 60 seconds

```bash
BASE=http://localhost:8080
# 1. register + login
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"you@demo.local","password":"DemoPass!123","displayName":"You"}'
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@demo.local","password":"DemoPass!123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

# 2. create a queue and enqueue work
curl -s -X POST $BASE/api/queues -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"default","maxConcurrency":16,"maxAttempts":5}'
curl -s -X POST $BASE/api/queues/default/messages -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: job-1' \
  -d '{"payload":"{\"workMs\":100}","priority":7}'

# 3. watch it flow
curl -s $BASE/api/queues/default/stats -H "Authorization: Bearer $TOKEN"
curl -s $BASE/monitor/load
```

Useful admin calls (JWT required): `GET /api/ratelimit/bans`, `GET /api/ratelimit/adaptive`,
`POST /api/ratelimit/ban/{ip}?minutes=5`, `DELETE /api/ratelimit/bans/{ip}`,
`GET /api/queues/default/dead`, `POST /api/queues/default/dead/{id}/requeue`,
`POST /api/workers/pause` / `resume`.

Response headers to show your examiner: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Multiplier`, `X-RateLimit-Mode`, `Retry-After`.

---

## The attack story (what to say in the viva)

1. **One IP floods the API** → token bucket empties in ~1 s → every request gets **429 + Retry-After**.
2. Denials become **strikes**; at 5 strikes the IP is **banned** (1, 2, 4… min, capped at 60).
3. The **monitor** sees load rise → the **adaptive multiplier** shrinks (up to 1.5× when idle, down to 0.2× under attack) → limits tighten by themselves.
4. Forged `X-Forwarded-For` rotation **cannot** dodge the limiter (`TRUST_PROXY_HEADERS=false`).
5. Everyone else keeps working. Bans persist in PostgreSQL. The gateway's **audit log** records every denial.

## Configuration highlights (`k8s/02-configmap.yaml`)

| Env | Default | Meaning |
|---|---|---|
| `RL_BASE_LIMIT_PER_SEC` / `RL_BURST` | 10 / 20 | Per-IP allowance (before adaptive multiplier) |
| `RL_STRIKE_BAN_THRESHOLD` | 5 | Denials before a ban |
| `RL_BAN_BASE_MINUTES` / `RL_BAN_MAX_MINUTES` | 1 / 60 | Exponential ban ladder |
| `TRUST_PROXY_HEADERS` | false | Only enable behind a trusted L7 proxy |
| `WORKER_MAX_CONCURRENCY` | 8 | Semaphore size per worker pod |
| `JWT_SECRET` | (secret) | HS256 signing key |

## Tests & drills

- `scripts/load_test.py` — N concurrent users, register/login/enqueue/poll.
- `scripts/attack_sim.py` — flood + XFF-spoof + ban-state report.
- Fault drills: `kubectl -n queueguard delete pod <worker-pod>` (messages recover via visibility timeout), `docker compose stop rate-limit-service` (gateway falls back to a local limiter — never fails open).

## Local development without Docker

Postgres + Redis on localhost (`deploy/db-init/init-databases.sql` creates the six DBs), then in each service dir: `mvn spring-boot:run` (Java 17, Maven 3.9+).
