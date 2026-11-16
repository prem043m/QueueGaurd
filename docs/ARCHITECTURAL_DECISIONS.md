# Architectural Decisions

## ADR-001: Microservices Architecture

**Status:** Accepted

**Context:** QueueGuard needs to be scalable, maintainable, and support multiple queue management use cases (interviews, hospitals, etc.)

**Decision:** Use microservices architecture with Spring Boot

**Consequences:**
- Each service can be independently deployed and scaled
- Clear separation of concerns between services
- Technology flexibility for different service needs
- Requires careful API design and inter-service communication

## ADR-002: Multi-tenant Design

**Status:** Accepted

**Context:** QueueGuard must support multiple organizations with complete data isolation

**Decision:** 
- Every business entity includes `organization_id` column
- All repository queries filter by organization ID
- Unique constraints include organization ID as first column
- Application-level authorization enforces tenant isolation

**Consequences:**
- Natural data partitioning
- Simple tenant-specific queries
- No cross-tenant data leakage
- PostgreSQL row-level security can be added later

## ADR-003: Separation of Infrastructure and Business Queues

**Status:** Accepted (Critical for Phase 2)

**Context:** Phase 1 has queue-service for infrastructure messaging. Phase 2 needs business queue management.

**Decision:**
- `queue-service` continues to own infrastructure message queues (Redis-backed)
- `queue-management-service` owns business queue logic and persistence
- Business queues are stored in `qms_db`, not `queue_db`
- No reuse of `QueueDef` or `Message` entities for business queues

**Consequences:**
- Clear service boundaries
- Each service can evolve independently
- Business queue operations don't depend on Redis
- PostgreSQL is authoritative for business queue state

## ADR-004: Transactional Outbox Pattern

**Status:** Accepted (Phase 2)

**Context:** Need reliable event publishing without coupling to external services

**Decision:** Implement transactional outbox pattern
- Outbox events stored in same transaction as business state changes
- Outbox events have their own lifecycle (PENDING, PUBLISHED, FAILED)
- Separate publisher process retrieves and publishes events
- Idempotency keys prevent duplicate event processing

**Consequences:**
- Atomic business operation and event creation
- No external service failures block business operations
- At-least-once delivery semantics
- Consumers must be idempotent

## ADR-005: State Machine for Queue Entries

**Status:** Accepted (Phase 2)

**Context:** Queue entries have complex lifecycle with many states and valid transitions

**Decision:** Centralized QueueStateMachine class
- Single source of truth for valid transitions
- All state changes validated through state machine
- Prevents invalid state transitions
- Enables consistent error messages

**Consequences:**
- Centralized validation logic
- Easy to audit state transitions
- Changes to state machine affect all operations
- Must ensure all code paths use state machine

## ADR-006: Token Generation Strategy

**Status:** Accepted (Phase 2)

**Context:** Need unique, human-readable tokens for queue participants

**Decision:** 
- Format: `{queueCode}-{date}-{sequence}`
- Example: `Q001-20241107-001`
- Date in `yyyyMMdd` format
- Sequence is 3-digit zero-padded
- Database uniqueness constraint as primary protection
- In-memory generation with fallback for concurrency

**Consequences:**
- Human-readable and traceable
- Natural daily reset (sequence restarts each day)
- Database constraint guarantees uniqueness
- Fallback handles edge cases

## ADR-007: Concurrency Control Strategy

**Status:** Accepted (Phase 2)

**Context:** Multiple operators may call next participant simultaneously. Need to prevent race conditions.

**Decision:** Multi-layered concurrency control
- **Pessimistic locking:** `SELECT FOR UPDATE SKIP LOCKED` for call-next operations
- **Optimistic locking:** `@Version` annotation on all mutable entities
- **Unique constraints:** Database-level prevention of duplicates
- **Application-level:** Validate state before transitions

**Consequences:**
- Strong consistency guarantees
- Prevents double-assignment
- Handles concurrent registrations safely
- May cause some transactions to fail and retry

## ADR-008: Authorization Model

**Status:** Accepted (Phase 1, extended in Phase 2)

**Context:** Need fine-grained permissions for queue operations

**Decision:**
- **ORG_ADMIN:** Full organization management
- **QUEUE_MANAGER:** Create/configure queues, manage entries, view statistics
- **INTERVIEWER:** Call next, start/complete service, mark no-show/skip
- **MEMBER:** Register self, view own entries, cancel own entries
- **PLATFORM_ADMIN:** Platform-level operations (not through membership)

**Consequences:**
- Role-based access control
- Tenant-scoped permissions
- Clear separation of duties
- Easy to extend with new roles

## ADR-009: Ordering Policy Extensibility

**Status:** Accepted (Phase 2)

**Context:** Need to support FIFO and priority-based ordering, with room for future strategies

**Decision:**
- `OrderingPolicy` enum with FIFO and PRIORITY_FIFO
- Ordering logic encapsulated in repository queries
- Different indexes for different ordering policies
- Easy to add new policies without changing core logic

**Consequences:**
- Deterministic ordering
- Efficient queries with appropriate indexes
- Extensible for future scheduling strategies
- Each policy has its own query implementation

## ADR-010: API Versioning

**Status:** Accepted

**Context:** Need to support API evolution without breaking clients

**Decision:**
- All endpoints versioned under `/api/v1/`
- Backward-compatible changes preferred
- Breaking changes require new version
- Deprecation before removal

**Consequences:**
- Clear API contracts
- Clients can migrate at their own pace
- Multiple versions may need support
- Version management overhead

## ADR-011: Event-Driven Architecture

**Status:** Accepted (Foundation in Phase 2)

**Context:** Need to support future real-time updates and notifications

**Decision:**
- All state changes create queue events (audit trail)
- Outbox events for external consumers
- Event contracts are versionable
- REST APIs remain authoritative

**Consequences:**
- Loose coupling between services
- Easy to add new consumers
- Event replay capability
- Eventually consistent views possible

## ADR-012: Test Strategy

**Status:** Accepted

**Context:** Need to ensure correctness of business logic and concurrency handling

**Decision:**
- **Unit tests:** Domain logic, state machine, validation
- **Integration tests:** Repository queries, service interactions
- **Concurrency tests:** Concurrent call-next, registration
- **API tests:** End-to-end endpoint testing
- Use Testcontainers for integration tests with real PostgreSQL

**Consequences:**
- High test coverage
- Confidence in correctness
- Integration tests require Docker
- Slower test suite

## ADR-013: Documentation First

**Status:** Accepted

**Context:** Need to maintain architectural clarity as system grows

**Decision:**
- Maintain up-to-date architecture documentation
- Document all ADRs
- Keep API design documented
- Update documentation with each phase

**Consequences:**
- Better team alignment
- Easier onboarding
- Documentation maintenance overhead
- Slows down development slightly

## Decision Log

| Date | Decision | Rationale |
|---|---|---|
| 2024-XX-XX | ADR-001: Microservices | Scalability, maintainability |
| 2024-XX-XX | ADR-002: Multi-tenant | Data isolation |
| 2024-XX-XX | ADR-003: Separation of queues | Service boundaries |
| 2024-XX-XX | ADR-004: Transactional outbox | Reliable events |
| 2024-XX-XX | ADR-005: State machine | Valid transitions |
| 2024-XX-XX | ADR-006: Token generation | Human-readable tokens |
| 2024-XX-XX | ADR-007: Concurrency control | Prevent race conditions |
| 2024-XX-XX | ADR-008: Authorization | Security |
| 2024-XX-XX | ADR-009: Ordering policy | Flexible scheduling |
| 2024-XX-XX | ADR-010: API versioning | Backward compatibility |
| 2024-XX-XX | ADR-011: Event-driven | Future extensibility |
| 2024-XX-XX | ADR-012: Test strategy | Quality assurance |
| 2024-XX-XX | ADR-013: Documentation | Maintainability |
