# Database design

## Ownership and tenant rule

Add `qms_db` for all business entities. `auth_db.users` remains the source for credentials. QMS stores `user_id` as an external reference; cross-database foreign keys are deliberately avoided. Every tenant-owned table has non-null `organization_id`. Every repository method accepts/filters by organization ID, and unique indexes begin with it. PostgreSQL row-level security is a recommended defense-in-depth later phase, not a replacement for application authorization.

## Core entities

| Entity | Essential columns and relationships |
|---|---|
| `organization` | `id`, slug (unique), name, status, created_at |
| `membership` | `id`, organization_id, user_id, status, joined_at; unique `(organization_id,user_id)` |
| `role` | platform-defined `PLATFORM_ADMIN`, `ORG_ADMIN`, `INTERVIEWER`, `STUDENT`, plus optional tenant custom role |
| `membership_role` | membership_id, role_id; unique pair |

## Phase 2 - Business Queue Engine

### Queue Configuration (`queue_configurations`)
| Column | Type | Nullable | Description |
|---|---|---|---|
| `id` | BIGSERIAL | NO | Primary key |
| `organization_id` | BIGINT | NO | Foreign key to organizations |
| `name` | VARCHAR(255) | NO | Queue display name |
| `description` | VARCHAR(1000) | YES | Queue description |
| `queue_code` | VARCHAR(50) | NO | Unique code within organization |
| `queue_type` | VARCHAR(20) | NO | Enum: GENERIC, COUNTER, APPOINTMENT, WALK_IN |
| `ordering_policy` | VARCHAR(20) | NO | Enum: FIFO, PRIORITY_FIFO |
| `status` | VARCHAR(20) | NO | Enum: DRAFT, OPEN, PAUSED, CLOSED, ARCHIVED |
| `maximum_capacity` | INTEGER | YES | Maximum entries allowed |
| `priority_enabled` | BOOLEAN | NO | Whether priority is enabled |
| `estimated_service_duration_seconds` | INTEGER | YES | Estimated service time for wait calculation |
| `created_by` | BIGINT | NO | User who created the queue |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NO | Last update timestamp |
| `version` | BIGINT | NO | Optimistic locking version |

**Unique Constraints:**
- `(organization_id, queue_code)` - Queue code must be unique per organization

**Indexes:**
- `idx_queue_config_org` on `organization_id`
- `idx_queue_config_org_status` on `(organization_id, status)`
- `idx_queue_config_code` on `queue_code`

### Queue Entry (`queue_entries`)
| Column | Type | Nullable | Description |
|---|---|---|---|
| `id` | BIGSERIAL | NO | Primary key |
| `organization_id` | BIGINT | NO | Foreign key to organizations |
| `queue_configuration_id` | BIGINT | NO | Foreign key to queue_configurations |
| `token_number` | VARCHAR(50) | NO | Unique token within organization |
| `external_reference` | VARCHAR(255) | YES | External reference ID |
| `participant_user_id` | BIGINT | YES | Auth user ID if authenticated |
| `participant_name` | VARCHAR(255) | NO | Participant display name |
| `participant_email` | VARCHAR(255) | YES | Participant email |
| `priority` | INTEGER | NO | Priority level (default 0) |
| `status` | VARCHAR(20) | NO | Enum: WAITING, CALLED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW, SKIPPED |
| `joined_at` | TIMESTAMPTZ | NO | When participant joined the queue |
| `called_at` | TIMESTAMPTZ | YES | When entry was called |
| `service_started_at` | TIMESTAMPTZ | YES | When service started |
| `completed_at` | TIMESTAMPTZ | YES | When service completed |
| `cancelled_at` | TIMESTAMPTZ | YES | When entry was cancelled |
| `estimated_wait_seconds` | INTEGER | YES | Estimated wait time |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NO | Last update timestamp |
| `version` | BIGINT | NO | Optimistic locking version |

**Unique Constraints:**
- `(organization_id, token_number)` - Token must be unique per organization

**Indexes:**
- `idx_queue_entry_org` on `organization_id`
- `idx_queue_entry_config` on `queue_configuration_id`
- `idx_queue_entry_org_config_status` on `(organization_id, queue_configuration_id, status)`
- `idx_queue_entry_org_status` on `(organization_id, status)`
- `idx_queue_entry_config_status_priority` on `(queue_configuration_id, status, priority DESC, joined_at, id)` for priority ordering
- `idx_queue_entry_token` on `token_number`
- `idx_queue_entry_participant_user` on `participant_user_id`
- `idx_queue_entry_joined_at` on `joined_at`

### Queue Assignment (`queue_assignments`)
| Column | Type | Nullable | Description |
|---|---|---|---|
| `id` | BIGSERIAL | NO | Primary key |
| `organization_id` | BIGINT | NO | Foreign key to organizations |
| `queue_entry_id` | BIGINT | NO | Foreign key to queue_entries |
| `operator_user_id` | BIGINT | NO | Auth user ID of the operator |
| `service_point` | VARCHAR(100) | YES | Service point identifier |
| `assigned_at` | TIMESTAMPTZ | NO | Assignment timestamp |
| `released_at` | TIMESTAMPTZ | YES | Release timestamp |
| `status` | VARCHAR(20) | NO | Enum: ASSIGNED, ACTIVE, RELEASED, COMPLETED |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |
| `version` | BIGINT | NO | Optimistic locking version |

**Unique Constraints:**
- Partial unique: `(queue_entry_id)` where `status IN ('ASSIGNED', 'ACTIVE')` - One active assignment per entry

**Indexes:**
- `idx_queue_assignment_org` on `organization_id`
- `idx_queue_assignment_entry` on `queue_entry_id`
- `idx_queue_assignment_operator` on `operator_user_id`
- `idx_queue_assignment_status` on `status`
- `idx_queue_assignment_org_entry_status` on `(organization_id, queue_entry_id, status)`

### Queue Event (`queue_events`)
| Column | Type | Nullable | Description |
|---|---|---|---|
| `id` | BIGSERIAL | NO | Primary key |
| `organization_id` | BIGINT | NO | Foreign key to organizations |
| `queue_configuration_id` | BIGINT | YES | Foreign key to queue_configurations |
| `queue_entry_id` | BIGINT | YES | Foreign key to queue_entries |
| `event_type` | VARCHAR(30) | NO | Enum: QUEUE_CREATED, QUEUE_UPDATED, QUEUE_OPENED, QUEUE_PAUSED, QUEUE_RESUMED, QUEUE_CLOSED, QUEUE_ARCHIVED, ENTRY_REGISTERED, ENTRY_CALLED, ENTRY_STARTED, ENTRY_COMPLETED, ENTRY_CANCELLED, ENTRY_NO_SHOW, ENTRY_SKIPPED, ENTRY_REQUEUED, ASSIGNMENT_CREATED, ASSIGNMENT_RELEASED |
| `actor_user_id` | BIGINT | YES | User who triggered the event |
| `event_payload` | TEXT | YES | Additional event data (JSON) |
| `occurred_at` | TIMESTAMPTZ | NO | Event timestamp |

**Indexes:**
- `idx_queue_event_org` on `organization_id`
- `idx_queue_event_config` on `queue_configuration_id`
- `idx_queue_event_entry` on `queue_entry_id`
- `idx_queue_event_type` on `event_type`
- `idx_queue_event_occurred_at` on `occurred_at`
- `idx_queue_event_org_config` on `(organization_id, queue_configuration_id)`

### Outbox Event (`outbox_events`)
| Column | Type | Nullable | Description |
|---|---|---|---|
| `id` | BIGSERIAL | NO | Primary key |
| `organization_id` | BIGINT | NO | Foreign key to organizations |
| `aggregate_type` | VARCHAR(100) | NO | Aggregate type (e.g., QueueEntry, QueueAssignment) |
| `aggregate_id` | BIGINT | NO | Aggregate ID |
| `event_type` | VARCHAR(100) | NO | Event type |
| `payload` | TEXT | YES | Event payload (JSON) |
| `created_at` | TIMESTAMPTZ | NO | Creation timestamp |
| `published_at` | TIMESTAMPTZ | YES | Publication timestamp |
| `processing_status` | VARCHAR(20) | NO | Enum: PENDING, PUBLISHED, FAILED |
| `retry_count` | INTEGER | NO | Number of retry attempts |
| `last_error` | TEXT | YES | Last error message |
| `idempotency_key` | VARCHAR(100) | YES | Idempotency key |
| `version` | BIGINT | NO | Optimistic locking version |

**Indexes:**
- `idx_outbox_org` on `organization_id`
- `idx_outbox_status` on `processing_status`
- `idx_outbox_aggregate` on `(aggregate_type, aggregate_id)`
- `idx_outbox_created_at` on `created_at`
- `idx_outbox_idempotency` on `idempotency_key` (partial index where not null)
- `idx_outbox_pending` on `(processing_status, created_at)` (partial index where status = 'PENDING')

## Interview Domain (Future Phase 3)

Interview specialization is owned by the same database: `interview_drive` (organization, queue configuration, registration window, status), `interview_panel` (drive, name, capacity, availability), `interview_panel_member` (panel, membership, role), `student_registration` (drive, membership, eligibility/status), and `interview_session` (drive, queue entry, panel, slot, outcome, started/completed timestamps). `queue_entry` has optional `drive_id` and `registration_id` to retain generic reuse while enabling the first application.

## Indexes and correctness

- `queue_entry(organization_id, queue_configuration_id, status, priority DESC, joined_at, id)` supports priority selection; use `(status, joined_at, id)` for FIFO.
- Unique `(organization_id, token_code)`, `(drive_id, participant_membership_id)`, `(drive_id, panel_name)`, and notification `(organization_id, idempotency_key)`.
- `appointment_slot(resource_id, starts_at, ends_at)` and a PostgreSQL exclusion constraint or transactional overlap check prevent panel double booking.
- Partial unique indexes enforce one active assignment and one active interview session per entry.
- Add `@Version`/row-version to queue entries, slots, assignments and sessions. Claim/call-next uses a short transaction with `SELECT ... FOR UPDATE SKIP LOCKED` or conditional update.

## Transaction boundaries

Create registration + queue entry + token + outbox event atomically. Assignment/call-next atomically locks candidate and panel/slot, writes assignment/session as applicable, changes entry status and inserts outbox event. Completion/no-show/cancellation similarly performs a single QMS transaction. A separate publisher may retry outbox delivery; notification delivery is at-least-once and idempotent. Never include existing queue-service Redis/PostgreSQL operations in these transactions.

## Phase 2 Specific Design

### Token Generation
- Format: `{queueCode}-{date}-{sequence}` where date is `yyyyMMdd`
- Example: `Q001-20241107-001`
- Unique within organization scope
- Date component allows daily reset and human readability
- Sequence is 3-digit zero-padded

### State Machine
- **Valid transitions:**
  - WAITING -> CALLED
  - WAITING -> CANCELLED
  - CALLED -> IN_SERVICE
  - CALLED -> NO_SHOW
  - CALLED -> WAITING (requeue)
  - IN_SERVICE -> COMPLETED
  - IN_SERVICE -> SKIPPED
- **Terminal states:** COMPLETED, CANCELLED, NO_SHOW, SKIPPED
- Invalid transitions are rejected with `409 Conflict`

### Concurrency Control
- **SELECT FOR UPDATE** with **SKIP LOCKED** for call-next operations
- **Optimistic locking** with `@Version` annotation on all mutable entities
- **Unique constraints** prevent duplicate tokens and concurrent operations
- **Partial unique indexes** prevent duplicate active assignments

### Ordering Policies
- **FIFO:** First In, First Out - ordered by `joined_at` then `id`
- **PRIORITY_FIFO:** Higher priority first, then FIFO within same priority
  - Ordered by `priority DESC, joined_at ASC, id ASC`

### Queue Status
- **DRAFT:** Queue created but not yet open
- **OPEN:** Queue is accepting registrations and can call entries
- **PAUSED:** Queue is temporarily paused, cannot call entries
- **CLOSED:** Queue is closed, cannot accept new registrations
- **ARCHIVED:** Queue is archived and read-only
