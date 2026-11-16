# QueueGuard: current architecture audit

## Verified implementation

QueueGuard is a Java 17/Spring Boot 3.2 multi-module system with seven services, PostgreSQL and Redis. Docker Compose runs the full stack; Kubernetes manifests provide a namespace, secrets/config, PostgreSQL, Redis, each service, ingress, HPA/PDB and network policies. The gateway is the public entry point on port 8080.

| Component | Current responsibility | Reuse decision |
|---|---|---|
| `api-gateway` | reverse proxy, request ID/client IP, JWT verification, IP/user rate-limit call, circuit breakers, buffered audit log, static dashboard route | Reuse and extend routing/authorization enforcement |
| `auth-service` | email/password registration/login and HS256 JWT issue/lookup | Reuse as identity foundation; evolve its data model/API |
| `queue-service` | named priority-ready queues, PostgreSQL durable messages, Redis ready-set, claim/ack/nack, retry, visibility reaper and DLQ | Reuse only as the **infrastructure work queue** |
| `worker-service` | polling consumer, semaphore concurrency, processing result persistence, pause/resume, monitor reporting | Reuse for notification/background jobs; do not make it an interview scheduler |
| `rate-limit-service` | Redis token buckets, IP strike/ban records, adaptive multiplier | Reuse unchanged except route policy configuration |
| `monitor-service` | health polling, stored metrics/load snapshots, adaptive-limit feedback, simple dashboard | Reuse and add business analytics separately |
| `queue-management-service` | Flyway-managed tenant, organization, membership and organization-role boundary | **Phase 1: Identity & Tenancy. Phase 2: Business Queue Engine** |

## Current persistence and flows

Each service owns a logical PostgreSQL database (`auth_db`, `queue_db`, `worker_db`, `ratelimit_db`, `monitor_db`, `gateway_db`, `qms_db`). `UserAccount` is globally unique by email and contains only identity/profile fields. JWTs carry subject, issue/expiry and optional `uid`/name claims; the gateway validates them and forwards trusted `X-QG-User-*` headers.

The generic queue has global `QueueDef.name` uniqueness and a `Message` record with priority, idempotency key, claim state, attempts, next-attempt time and optimistic-lock version. Redis holds the ready ordering; PostgreSQL remains durable source of message state. Workers claim then ack/nack; nack uses backoff and eventually DLQ; a visibility reaper returns abandoned claims. This is sound infrastructure messaging, not a participant-facing waiting queue.

The browser UI is a monitor-service static page proxied from `/`; it is not a business application. Existing scripts demonstrate health, auth, queue handling, concurrent load and rate-limit attacks. There are no automated Java test directories, OpenAPI contract, CI workflow, database migration tooling, tenant isolation, roles, WebSocket/SSE channel, or React application.

## Phase 2 Additions

### queue-management-service (QMS) - Business Queue Engine

**New Database (`qms_db` Phase 2):**
- `queue_configurations` - Business queue definitions
- `queue_entries` - Participants in queues with tokens
- `queue_assignments` - Operator assignments to entries
- `queue_events` - Audit trail of queue operations
- `outbox_events` - Transactional outbox for event publishing

**New Domain Model:**
- QueueConfiguration: DRAFT, OPEN, PAUSED, CLOSED, ARCHIVED
- QueueEntry: WAITING, CALLED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW, SKIPPED
- QueueAssignment: ASSIGNED, ACTIVE, RELEASED, COMPLETED
- OrderingPolicy: FIFO, PRIORITY_FIFO
- QueueType: GENERIC, COUNTER, APPOINTMENT, WALK_IN

**New Service Capabilities:**
- Queue configuration CRUD and lifecycle management
- Participant registration with unique token generation
- Position calculation based on ordering policy
- Call next with concurrency protection (SELECT FOR UPDATE SKIP LOCKED)
- Complete queue lifecycle: WAITING -> CALLED -> IN_SERVICE -> COMPLETED
- Exception paths: CANCELLED, NO_SHOW, SKIPPED
- Transactional outbox pattern for reliable event publishing
- Audit trail with QueueEvent records

**New API Endpoints (21 total):**
- `/api/v1/organizations/{orgId}/queues` - Queue configuration management
- `/api/v1/organizations/{orgId}/queues/{queueId}/open` - Open queue
- `/api/v1/organizations/{orgId}/queues/{queueId}/pause` - Pause queue
- `/api/v1/organizations/{orgId}/queues/{queueId}/resume` - Resume queue
- `/api/v1/organizations/{orgId}/queues/{queueId}/close` - Close queue
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries` - Participant registration
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/position` - Position lookup
- `/api/v1/organizations/{orgId}/queues/{queueId}/call-next` - Call next participant
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/start` - Start service
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/complete` - Complete service
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/no-show` - Mark no-show
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/requeue` - Requeue entry
- `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/skip` - Skip entry
- `/api/v1/organizations/{orgId}/queues/{queueId}/stats` - Queue statistics
- `/api/v1/organizations/{orgId}/queues/{queueId}/current` - Current queue state

**Authorization:**
- ORG_ADMIN: Full queue management
- QUEUE_MANAGER: Create/configure queues, manage entries, view statistics
- INTERVIEWER: Call next, start/complete service, mark no-show/skip
- MEMBER: Register self, view own entries, cancel own entries
- PLATFORM_ADMIN: Platform-level operations (not through membership)

## Constraints and gaps

1. Authentication proves identity but has no organization membership, role claim, refresh/revocation, or authorization policy.
2. Existing queue names and idempotency keys are global, so they cannot represent tenant-scoped business queues.
3. Queue message lifecycle (`PENDING/CLAIMED/DONE/DEAD`) describes work delivery, not attendance, scheduling, cancellation, no-show or interview completion.
4. Gateway authentication protects `/api/**`, but services do not themselves enforce roles. Administrative rate-limit and worker endpoints therefore need explicit authorization before exposure to organization users.
5. Infrastructure metrics cannot answer operational business questions such as wait time, no-show rate, panel utilization, or drive throughput.

## Phase 2 Resolved Constraints

✅ **Constraint 1:** QMS now has organization membership and role-based authorization.

✅ **Constraint 2:** Business queue entities (QueueConfiguration, QueueEntry) are tenant-scoped with organization_id and use tenant-specific queue codes.

✅ **Constraint 3:** Business queue lifecycle (WAITING, CALLED, IN_SERVICE, COMPLETED, etc.) is separate from infrastructure message lifecycle.

✅ **Constraint 4:** QMS enforces tenant-scoped authorization for all operations.

⚠️ **Constraint 5:** Business queue statistics are now available through QMS, but infrastructure monitoring remains in monitor-service.

## Boundary to preserve

Do **not** rename, repurpose, or move `QueueDef`/`Message` into interview entities. Introduce one business-domain service (named `queue-management-service` below) which owns tenant, scheduling and business queue state. It emits notification/background commands into the existing queue-service. This retains existing API contracts and all current demo scripts.

## Phase 2 Specific Preserved Boundaries

✅ **Infrastructure vs Business Queues:**
- `queue-service` handles infrastructure messages (work delivery)
- `queue-management-service` handles business queues (participant management)
- No cross-contamination between the two

✅ **Database Ownership:**
- QMS accesses only `qms_db`
- No direct queries to `auth_db`, `queue_db`, or other service databases
- External references use `auth_user_id` without joining

✅ **Event Architecture:**
- Outbox events stored in same transaction as business operations
- Events are atomic and reliable
- Future consumers can process events without blocking business operations

✅ **Authorization:**
- All business operations validate organization membership
- All business operations validate role-based permissions
- Cross-tenant access is prevented at application level
