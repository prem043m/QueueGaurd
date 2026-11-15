# QueueGuard Phase 3 - Student Interview Management System
## Implementation Plan

**Version:** 2.7
**Date:** 2026-11-15
**Status:** Milestones 3.1-3.5 implemented and verified; Milestones 3.6-3.7 outstanding

Version 2.7 incorporates the verified CandidateRound lifecycle and Business
Queue Engine integration for Milestone 3.5. Milestones 3.6-3.7 remain planned.

## 1. Scope and principles

Phase 3 adds placement drives, candidate profiles and applications, interview
rounds, interviewer assignments, evaluations, and round-specific queue
integration to the queue-management service.

The implementation must:

1. Reuse the existing `QueueService`, `TenantService`, token generation, and
   queue state machine.
2. Keep `QueueEntry` as the sole owner of queue lifecycle state.
3. Enforce organization and student ownership in the database, not only in
   application code.
4. Make capacity reservation and registration idempotent and transactionally
   safe.
5. Keep Phase 3 evaluation submissions final; corrections are out of scope.

## 2. Repository audit and verified capabilities

The following facts were verified in the repository:

| Capability | Evidence | Planning consequence |
|---|---|---|
| PostgreSQL 15 | `deploy/db-init`, project database configuration | `UNIQUE NULLS NOT DISTINCT` is available. |
| Queue configuration creation | `services/queue-management-service/src/main/java/com/queueguard/qms/service/QueueService.java:53` | Create the queue configuration before the interview round. |
| Authorized participant registration | `QueueService.registerParticipant(actor, organizationId, queueId, request)` at `QueueService.java:338` | Coordinator-triggered registration must call this method. |
| Tenant authorization | `QueueService.createQueueConfig` and `registerParticipant` call `TenantService.active` | Do not bypass these checks or invent a system principal. |
| Queue transition validation | `QueueStateMachine` | Queue status changes must go through existing queue operations. |
| Token concurrency | `TokenGenerator` uses insert-on-conflict and row locking | Reuse it; do not allocate interview tokens independently. |
| Existing queue web surface | `qms/web/QueueController.java` | New interview code wraps the service API rather than modifying QMS behavior. |
| QueueEntry tenant key | `V2__business_queue_engine.sql:109-112` | `(organization_id, id)` is an existing referenced unique key. |
| QueueEntry queue column and FK | `V2__business_queue_engine.sql:32-52`; `domain/QueueEntry.java:20-21` | `queue_configuration_id` exists, is non-null, and is linked to QueueConfiguration; the entity maps it as `@Column(name = "queue_configuration_id")`. |
| QueueConfiguration tenant key | `V2__business_queue_engine.sql:140-147`; `domain/QueueConfiguration.java:11-12` | `(organization_id, id)` is an existing composite referenced key. |
| Membership tenant key | `V1__identity_and_tenancy.sql:9-17` | `(organization_id, auth_user_id)` is the existing membership unique key. |
| Queue event enum and outbox calls | `domain/QueueEventType.java:6-23`; `QueueService.java:327-841` | Existing queue audit/outbox events must not be duplicated. |
| Queue transaction annotations | `QueueService.java:269-338` | `createQueueEntry` and `registerParticipant` use `@Transactional`; propagation is not explicitly declared. |

### Inspected repository files

- `docs/PHASE_3_IMPLEMENTATION_PLAN.md`
- `services/queue-management-service/src/main/resources/db/migration/V2__business_queue_engine.sql`
- `services/queue-management-service/src/main/resources/db/migration/V1__identity_and_tenancy.sql`
- `services/queue-management-service/src/main/java/com/queueguard/qms/service/QueueService.java`
- `services/queue-management-service/src/main/java/com/queueguard/qms/domain/QueueEventType.java`
- `services/queue-management-service/src/main/java/com/queueguard/qms/domain/QueueEntry.java`
- `services/queue-management-service/src/main/java/com/queueguard/qms/domain/QueueConfiguration.java`
- `services/queue-management-service/src/main/java/com/queueguard/qms/service/TokenGenerator.java`
- `services/queue-management-service/src/main/java/com/queueguard/qms/service/QueueStateMachine.java`
- `services/queue-management-service/src/main/java/com/queueguard/qms/web/QueueController.java`

No repository evidence was found for a trusted background execution identity
that can safely pass `TenantService.active()`. Automated background
registration is therefore deferred.

## 3. Architecture

Package: `com.queueguard.qms.interview`

### Services

Eight Phase 3 business services are proposed:

1. `PlacementDriveService`
2. `CandidateProfileService`
3. `CandidateApplicationService`
4. `EligibilityService`
5. `InterviewRoundService`
6. `InterviewerAssignmentService`
7. `CandidateRoundService`
8. `InterviewEvaluationService`

`InterviewQueueService` is an integration component, not a ninth independent
business service. It delegates queue operations to `QueueService` and maps
queue views into interview responses.

### State ownership

`QueueEntry.status` is the only authoritative queue lifecycle state.
`CandidateRound` contains the relationship to the queue entry and
interview-specific information only. It must not contain a second `status`
column. API responses that expose a candidate round obtain queue status by
joining/loading its `QueueEntry`; they never read or write a copied value.

The two state machines remain separate:

```text
CandidateRound.result/decision
        |
        +-- interview decision state (QUALIFIED, DISQUALIFIED, SELECTED, HOLD)

CandidateRound.queueEntryId --> QueueEntry.status
                                  |
                                  +-- WAITING -> CALLED -> IN_SERVICE
                                      -> COMPLETED / NO_SHOW / SKIPPED
                                      -> WAITING (REQUEUED)
```

There is no reconciliation or recovery process for a duplicated status because
no duplicate is persisted. A missing queue entry is a referential-integrity
failure and must fail the transaction rather than produce a success-shaped
response.

## 4. Domain model

All Phase 3 tables carry `organization_id`, timestamps, and optimistic
versioning where applicable.

### CandidateProfile

Fields: `userId`, `studentName`, `registrationNumber`, `email`, `phone`,
`department`, `graduationYear`, `cgpa`, `resumeReference`, `skills`.

The profile belongs to the student membership identified by
`(organization_id, user_id)`.

### PlacementDrive

Fields: `name`, `description`, `registrationCapacity`, lifecycle status,
registration dates, and ownership/audit fields.

`registrationCapacity` is a required positive integer:

```sql
registration_capacity INTEGER NOT NULL CHECK (registration_capacity > 0)
```

Acceptance counts applications whose `capacity_reserved_at IS NOT NULL`.
Capacity changes acquire the same `placement_drives` row lock used by
acceptance (`SELECT ... FOR UPDATE` on `(organization_id, id)`) and cannot
reduce `registrationCapacity` below the current number of reservations.
The drive table also provides the tenant-scoped key required by child
relationships:

```sql
UNIQUE (organization_id, id)
```

### CandidateApplication

Fields: `applicationNumber`, `eligibilityStatus`, `eligibilityReason`,
`applicationStatus`, `appliedAt`, `withdrawalReason`, `candidateProfileId`,
`studentUserId`, and `capacityReservedAt`.

`studentUserId` is retained so ownership is represented in the application
row and enforced by a composite foreign key.
`applicationNumber` is generated by the database-backed
`application_number_counters` row keyed by `(organization_id,
placement_drive_id)`. An atomic PostgreSQL `INSERT ... ON CONFLICT DO UPDATE
... RETURNING` increments and returns the next value. The allocation must run
in the same transaction as application persistence. If that transaction rolls
back, the counter increment rolls back too and the number can be reused. A
committed allocation followed by a later application deletion or an allocation
performed outside the application transaction can leave a gap; numbers are
unique and increasing, not a gapless ledger.

The two state machines are independent:

```text
Eligibility: PENDING -> ELIGIBLE
                      \-> INELIGIBLE

Application: SUBMITTED -> ACCEPTED -> WITHDRAWN
                      \-> REJECTED
             ELIGIBLE applications may also be WITHDRAWN.
```

An application starts as `eligibilityStatus = PENDING` and
`applicationStatus = SUBMITTED`. Only an `ELIGIBLE` application may become
`ACCEPTED`; an `INELIGIBLE` application cannot be accepted. Eligibility is
approved by `PENDING -> ELIGIBLE` or rejected by `PENDING -> INELIGIBLE`.
Application rejection is allowed only before acceptance, from `SUBMITTED`,
`ELIGIBLE`, or `INELIGIBLE`, and does not reserve capacity. Withdrawal is
allowed from `SUBMITTED`, `ELIGIBLE`, or `ACCEPTED`; an accepted withdrawal
retains its non-null `capacity_reserved_at`. Re-acceptance is idempotent only
when the application is already accepted. All other transitions, including `ACCEPTED -> REJECTED`, are invalid.

The state transition matrix is explicit about the independent fields:

| Eligibility status | Application status | Permitted operation | Resulting states | Capacity effect |
|---|---|---|---|---|
| PENDING | SUBMITTED | Approve eligibility | ELIGIBLE / SUBMITTED | None |
| PENDING | SUBMITTED | Reject eligibility | INELIGIBLE / SUBMITTED | None |
| PENDING, ELIGIBLE, or INELIGIBLE | SUBMITTED | Reject application | unchanged eligibility / REJECTED | No reservation |
| ELIGIBLE | SUBMITTED | Accept application | ELIGIBLE / ACCEPTED | Set `capacity_reserved_at`; count toward capacity |
| INELIGIBLE | SUBMITTED | Accept application | Invalid; reject operation | No reservation |
| PENDING, ELIGIBLE, or INELIGIBLE | SUBMITTED | Withdraw application | unchanged eligibility / WITHDRAWN | No reservation |
| ELIGIBLE | ACCEPTED | Withdraw application | ELIGIBLE / WITHDRAWN | Retain existing reservation |
| Any | REJECTED or WITHDRAWN | Any application operation | Invalid; terminal state | Reservation remains absent |
| Any | ACCEPTED | Reject application | Invalid transition | Existing reservation remains |

`ELIGIBLE` and `INELIGIBLE` are eligibility values only; they are never
application-status values. `REJECTED` and `WITHDRAWN` are terminal application
states. Eligibility approval/rejection does not itself accept or reject the
application.

### InterviewRound

Fields: `roundNumber`, `name`, `description`, `roundType`,
`scheduledStartAt`, `scheduledEndAt`, `queueConfigurationId`, `status`,
`qualificationCriteria`, and `createdBy`.

`queueConfigurationId` is `NOT NULL`. The queue configuration is created first,
then the round is created with that ID.
`roundNumber` is a positive integer and `scheduledEndAt` must be later than
`scheduledStartAt`.

### InterviewerAssignment

Fields: `interviewRoundId`, `interviewerUserId`, nullable `panelName`,
`servicePoint`, `assignedAt`, and assignment status.

Membership remains the authorization relationship for the interviewer.
The NULL-aware unique key includes released rows. To assign the same
interviewer to the same round and panel again, reactivate the existing
assignment row rather than inserting a duplicate row.

### CandidateRound

Fields: `candidateApplicationId`, `interviewRoundId`, `placementDriveId`,
`queueConfigurationId`, `queueEntryId`,
`result`, `decisionReason`, `decidedBy`, and `decidedAt`.

There is deliberately no `status` field. `queueEntryId` is `NOT NULL` because
every CandidateRound represents an actual registered queue entry.
The relationship also has an explicit composite foreign key to the existing
QueueEntry tenant key. CandidateRound owns the final progression decision;
detailed submitted evaluation data belongs exclusively to InterviewEvaluation.
`placementDriveId` is persisted to make the same-drive relationship
enforceable by composite foreign keys.
`queueConfigurationId` is persisted to make same-queue relationship
enforceable by composite foreign keys.

### InterviewEvaluation

Fields: `candidateRoundId`, `interviewerUserId`, technical, communication,
problem-solving and overall scores, recommendation, feedback, and
`submittedAt`. Evaluations are immutable after submission in Phase 3. The
entity rejects JPA updates and deletes; this does not block direct SQL or bulk
database updates, which require a PostgreSQL trigger or equivalent database
control for hard immutability.
Submission is idempotent for one candidate round and interviewer.

## 5. Database design

### Candidate profile ownership

Candidate profiles must contain:

```sql
UNIQUE (organization_id, id, user_id)
```

Candidate applications must contain:

```sql
FOREIGN KEY (
    organization_id,
    candidate_profile_id,
    student_user_id
)
REFERENCES candidate_profiles (
    organization_id,
    id,
    user_id
)
```

The existing membership foreign key remains appropriate. The composite
foreign key can reference a profile only when the application organization,
profile ID, and student user all match the same profile row. Consequently, a
student cannot submit an application using another student's profile, even if
the profile ID is known. A PostgreSQL `CHECK` with a subquery is not used.

The membership ownership relationship is:

```sql
FOREIGN KEY (organization_id, user_id)
    REFERENCES memberships (organization_id, auth_user_id)
```

This uses the verified V1 constraint
`uq_membership_org_user UNIQUE (organization_id, auth_user_id)`.

### Interviewer panel uniqueness

PostgreSQL ordinary unique constraints treat NULL values as distinct. The
Phase 3 design therefore uses PostgreSQL 15's explicit NULL equality:

```sql
UNIQUE NULLS NOT DISTINCT (
    organization_id,
    interview_round_id,
    interviewer_user_id,
    panel_name
)
```

This prevents two assignments for the same organization, round, interviewer,
and panel, including two assignments whose `panel_name` is NULL. The ordinary
`UNIQUE` constraint is not claimed to provide this behavior.

Required integration test:

1. Insert an assignment with `panel_name = NULL`.
2. Insert a second assignment with the same organization, round, interviewer,
   and `panel_name = NULL`.
3. Assert that the second insert fails with a uniqueness violation.

Interviewer assignments also require:

```sql
UNIQUE (organization_id, id)
FOREIGN KEY (organization_id, interview_round_id)
    REFERENCES interview_rounds (organization_id, id)
FOREIGN KEY (organization_id, interviewer_user_id)
    REFERENCES memberships (organization_id, auth_user_id)
```

The membership reference uses V1's `uq_membership_org_user`. The round
reference is an additive Phase 3 key on `interview_rounds`; it remains
organization-scoped.

### CandidateRound/QueueEntry cardinality

Candidate rounds must contain:

```sql
UNIQUE (organization_id, id)
```

Candidate applications and interview rounds must each contain a drive-scoped
key and relationship:

```sql
-- candidate_applications
UNIQUE (organization_id, id, placement_drive_id)
FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives (organization_id, id)

-- interview_rounds
UNIQUE (organization_id, id)
UNIQUE (organization_id, id, placement_drive_id)
FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives (organization_id, id)
```

    InterviewRound's queue configuration relationship is also tenant-scoped:

    ```sql
    FOREIGN KEY (organization_id, queue_configuration_id)
        REFERENCES queue_configurations (organization_id, id)
    ```

    The referenced `(organization_id, id)` key already exists for queue
    configurations in the existing QMS schema.

    To enforce that a CandidateRound's QueueEntry belongs to the InterviewRound's
    queue, the Phase 3 migration must add these composite keys and relationships:

    ```sql
    -- interview_rounds
    UNIQUE (
        organization_id,
        id,
        placement_drive_id,
        queue_configuration_id
    )

    -- queue_entries: additive key required by this relationship
    UNIQUE (
        organization_id,
        id,
        queue_configuration_id
    )
    ```

The existing V2 migration verifies `queue_configurations
UNIQUE (organization_id, id)` and `queue_entries
UNIQUE (organization_id, id)`. It also defines the existing
`queue_entries.queue_configuration_id BIGINT NOT NULL` column and the
organization-scoped foreign key
`(organization_id, queue_configuration_id) ->
queue_configurations(organization_id, id)`. The `QueueEntry` entity maps the
same column as `queueConfigurationId`; there is no alternate `queue_id`
column or `@JoinColumn` mapping. V2 does not provide the
`queue_entries (organization_id, id, queue_configuration_id)` key. That
additive unique key is therefore required in the new Phase 3 migration; V1
and V2 remain unchanged.

    CandidateRound must then contain:

    ```sql
FOREIGN KEY (
    organization_id, candidate_application_id, placement_drive_id
)
REFERENCES candidate_applications (
    organization_id, id, placement_drive_id
)
FOREIGN KEY (
    organization_id,
    interview_round_id,
    placement_drive_id,
    queue_configuration_id
)
REFERENCES interview_rounds (
    organization_id,
    id,
    placement_drive_id,
    queue_configuration_id
)
FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives (organization_id, id)
FOREIGN KEY (
    organization_id,
    queue_configuration_id
)
REFERENCES queue_configurations (organization_id, id)
UNIQUE (
    organization_id,
    candidate_application_id,
    interview_round_id
)
UNIQUE (organization_id, queue_entry_id)
FOREIGN KEY (
    organization_id,
    queue_entry_id,
    queue_configuration_id
)
REFERENCES queue_entries (
    organization_id,
    id,
    queue_configuration_id
)
```

The additional `placement_drive_id` column is required because a foreign key
cannot otherwise prove that the application and round belong to the same
drive. The `queue_configuration_id` column and the additional QueueEntry key
are required because the existing V2 keys do not prove that the QueueEntry
belongs to the InterviewRound's queue. The composite application, round,
queue, and drive relationships make cross-drive and cross-queue
CandidateRound rows impossible at the database layer.

Every CandidateRound references exactly one non-null QueueEntry. The
`UNIQUE (organization_id, queue_entry_id)` constraint means a QueueEntry can
be referenced by at most one CandidateRound. Other QueueEntry rows may exist
without a CandidateRound because QueueGuard's QMS is a reusable queue engine;
the relationship is not intended to claim that every queue entry is an
interview candidate.

The first CandidateRound unique constraint ensures one CandidateRound per
application per interview round, including concurrent registration requests.
The organization component preserves tenant isolation. The existing V2
`uq_queue_entry_org_id UNIQUE (organization_id, id)` remains verified, while
the three-column QueueEntry key above is the additive key needed for the
same-queue foreign key.

### Evaluation and domain checks

Interview evaluations must contain:

```sql
UNIQUE (organization_id, id)
UNIQUE (organization_id, candidate_round_id, interviewer_user_id)
FOREIGN KEY (organization_id, candidate_round_id)
    REFERENCES candidate_rounds (organization_id, id)
CHECK (technical_score BETWEEN 0 AND 100)
CHECK (communication_score BETWEEN 0 AND 100)
CHECK (problem_solving_score BETWEEN 0 AND 100)
CHECK (overall_score BETWEEN 0 AND 100)
```

Interviewer membership relationships are explicitly organization-scoped:

```sql
-- interviewer_assignments
FOREIGN KEY (organization_id, interviewer_user_id)
    REFERENCES memberships (organization_id, auth_user_id)

-- interview_evaluations
FOREIGN KEY (organization_id, interviewer_user_id)
    REFERENCES memberships (organization_id, auth_user_id)
```

These use the same verified V1 `uq_membership_org_user` key. The
CandidateRound foreign key above also makes the evaluation's
`candidate_round_id` organization-safe; an evaluation cannot point to a
CandidateRound in another organization.

The planned domain checks are:

```sql
CHECK (cgpa BETWEEN 0 AND 10)
CHECK (round_number > 0)
CHECK (scheduled_end_at > scheduled_start_at)
UNIQUE (organization_id, placement_drive_id, round_number)
UNIQUE (organization_id, placement_drive_id, application_number)
```

The application-number uniqueness constraint is scoped to an organization and
drive. V5 creates `application_number_counters`, keyed by organization and
drive. The repository allocator uses one PostgreSQL upsert with `RETURNING` to
atomically increment the counter; application code must not use
`MAX(application_number) + 1`. V5 seeds counters from existing applications.
Allocation participates in the surrounding application transaction. A rolled
back allocation can be reused; committed allocations are unique and increasing
but not guaranteed gapless.

Evaluation submission checks for an existing row under the unique key. A
matching already-submitted evaluation is returned unchanged (idempotent
retry); a conflicting payload is rejected. There is no update path after the
first successful submission.

### Capacity reservation

`candidate_applications.capacity_reserved_at TIMESTAMPTZ` defines the policy:

- Set it once, in the same transaction that changes an application to
  `ACCEPTED`.
- It remains non-null after withdrawal.
- Every application with a non-null value counts toward capacity.
- An application rejected before acceptance has a null value and consumes no
  capacity.
- Phase 3 does not permit `ACCEPTED -> REJECTED`. An accepted application may
  transition to `WITHDRAWN`, but its reservation is retained.
- Re-acceptance is idempotent: it does not replace the original timestamp.

The acceptance transaction executes `SELECT ... FOR UPDATE` on the
`placement_drives` row identified by `(organization_id, drive_id)`. Every
acceptance operation for that drive must acquire this same row lock before
counting rows with `capacity_reserved_at IS NOT NULL`; it then compares that
count with the positive `registrationCapacity` and sets the timestamp only if
capacity remains. Withdrawal never decrements the count. Capacity updates use
the same drive-row lock and must reject a new value below the current
reservation count. All retry paths handle a unique/idempotency conflict by
reloading the application and returning its current state.

The concurrency integration test starts more acceptance transactions than the
drive capacity and verifies that no more than capacity applications receive a
non-null reservation timestamp, including when all transactions target the
same drive concurrently.

## 6. Queue configuration and round creation

The creation order is mandatory:

1. Check coordinator authorization.
2. Call the verified `QueueService.createQueueConfig(actor, orgId, request)`
   method.
3. Use the returned queue configuration ID to create `InterviewRound`.
4. Publish/open the round only after its queue configuration exists and is
   valid for the organization.

There is no temporary null queue configuration and no round-before-queue
workflow.

## 7. Registration execution model

Registration is coordinator-triggered in Phase 3. The coordinator must have
`ORG_ADMIN` or `QUEUE_MANAGER` permission. The implementation calls the
existing `QueueService.registerParticipant()` method with the coordinator as
actor; it does not call a lower-level repository method.

This preserves `TenantService.active()`, existing permission checks, token
allocation, and queue event behavior. No system user is introduced, and no
assumption is made that a synthetic identity can pass tenant authorization.

Safe retry behavior:

- The database unique key `(organization_id, candidate_application_id,
  interview_round_id)` defines the idempotent registration identity.
- Registration locks the interview round and then the application. A retry
  returns the existing CandidateRound and its QueueEntry without registering
  another participant.
- The existing `registerParticipant` method is `@Transactional` and delegates
  to `createQueueEntry`, which is also `@Transactional`. Spring's default
  propagation is `REQUIRED`, so calls made through the Spring proxy join an
  transaction. CandidateRoundService and QueueService use the QMS datasource
  and transaction manager; a PostgreSQL failure-injection test verifies
  rollback of queue, audit, outbox, token sequence, and CandidateRound writes.
- A durable registration-attempt/reconciliation table is unnecessary for this
  verified single-database transaction.
- Background automatic registration is deferred until a trusted internal
  execution mechanism is verified in the repository.

## 8. REST API inventory

The Phase 3 inventory contains **63 endpoints**, **9 controllers**, and
**8 business services plus one queue integration component**.

HTTP totals are **33 POST**, **23 GET**, **2 PATCH**, **3 PUT**, and
**2 DELETE**.
The following table is the complete inventory; each endpoint is counted once.
Controller totals are: PlacementDrive 10, CandidateProfile 5, Application 8,
InterviewRound 9, InterviewerAssignment 6, CandidateRound 9,
InterviewEvaluation 4, InterviewQueue 8, and Statistics 4. A normalized audit
of method plus path found no duplicate HTTP mappings.

| # | Method | Full path | Controller | Required role | Short purpose |
|---:|---|---|---|---|---|
| 1 | POST | `/drives` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Create drive |
| 2 | GET | `/drives` | PlacementDriveController | MEMBER, ORG_ADMIN, QUEUE_MANAGER | List drives |
| 3 | GET | `/drives/{id}` | PlacementDriveController | MEMBER, ORG_ADMIN, QUEUE_MANAGER | Get drive |
| 4 | PUT | `/drives/{id}` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Replace drive details |
| 5 | POST | `/drives/{id}/publish` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Publish drive |
| 6 | POST | `/drives/{id}/open` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Open registration |
| 7 | POST | `/drives/{id}/close` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Close registration |
| 8 | POST | `/drives/{id}/start` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Start drive |
| 9 | POST | `/drives/{id}/complete` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Complete drive |
| 10 | POST | `/drives/{id}/cancel` | PlacementDriveController | ORG_ADMIN, QUEUE_MANAGER | Cancel drive |
| 11 | POST | `/candidates/profiles` | CandidateProfileController | MEMBER | Create own profile |
| 12 | GET | `/candidates/profiles/me` | CandidateProfileController | MEMBER | Get own profile |
| 13 | GET | `/candidates/profiles/{id}` | CandidateProfileController | MEMBER (own), ORG_ADMIN, QUEUE_MANAGER | Get profile |
| 14 | GET | `/candidates/profiles` | CandidateProfileController | ORG_ADMIN, QUEUE_MANAGER | List profiles |
| 15 | PUT | `/candidates/profiles/{id}` | CandidateProfileController | MEMBER (own), ORG_ADMIN, QUEUE_MANAGER | Replace profile |
| 16 | POST | `/drives/{driveId}/apply` | ApplicationController | MEMBER | Submit application |
| 17 | GET | `/applications/me` | ApplicationController | MEMBER | List own applications |
| 18 | GET | `/drives/{driveId}/applications` | ApplicationController | ORG_ADMIN, QUEUE_MANAGER | List drive applications |
| 19 | GET | `/applications/{id}` | ApplicationController | MEMBER (own), ORG_ADMIN, QUEUE_MANAGER | Get application |
| 20 | PATCH | `/applications/{id}/eligibility` | ApplicationController | ORG_ADMIN, QUEUE_MANAGER | Approve or reject pending eligibility |
| 21 | POST | `/applications/{id}/withdraw` | ApplicationController | MEMBER (own), ORG_ADMIN, QUEUE_MANAGER | Withdraw application |
| 22 | POST | `/applications/{id}/accept` | ApplicationController | ORG_ADMIN, QUEUE_MANAGER | Accept eligible application and reserve capacity |
| 23 | POST | `/applications/{id}/reject` | ApplicationController | ORG_ADMIN, QUEUE_MANAGER | Reject application before acceptance |
| 24 | GET | `/applications/{id}/rounds` | CandidateRoundController | MEMBER (own), ORG_ADMIN, QUEUE_MANAGER | List candidate rounds |
| 25 | POST | `/drives/{driveId}/rounds` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Create round and queue |
| 26 | GET | `/drives/{driveId}/rounds` | InterviewRoundController | MEMBER, ORG_ADMIN, QUEUE_MANAGER | List rounds |
| 27 | GET | `/rounds/{id}` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Get round |
| 28 | PUT | `/rounds/{id}` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Replace round details |
| 29 | DELETE | `/rounds/{id}` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Delete DRAFT round |
| 30 | POST | `/rounds/{id}/open` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Open round |
| 31 | POST | `/rounds/{id}/pause` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Pause round |
| 32 | POST | `/rounds/{id}/resume` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Resume round |
| 33 | POST | `/rounds/{id}/close` | InterviewRoundController | ORG_ADMIN, QUEUE_MANAGER | Close round |
| 34 | POST | `/rounds/{roundId}/assignments` | InterviewerAssignmentController | ORG_ADMIN, QUEUE_MANAGER | Assign interviewer |
| 35 | GET | `/rounds/{roundId}/assignments` | InterviewerAssignmentController | ORG_ADMIN, QUEUE_MANAGER | List assignments |
| 36 | GET | `/assignments/me` | InterviewerAssignmentController | INTERVIEWER | List own assignments |
| 37 | GET | `/assignments/{id}` | InterviewerAssignmentController | ORG_ADMIN, QUEUE_MANAGER, own INTERVIEWER | Get assignment |
| 38 | PATCH | `/assignments/{id}` | InterviewerAssignmentController | ORG_ADMIN, QUEUE_MANAGER | Update assignment |
| 39 | DELETE | `/assignments/{id}` | InterviewerAssignmentController | ORG_ADMIN, QUEUE_MANAGER | Remove assignment |
| 40 | GET | `/rounds/{roundId}/candidates` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | List round candidates |
| 41 | POST | `/rounds/{roundId}/applications/{applicationId}/register` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER | Register application through QueueService |
| 42 | POST | `/applications/{applicationId}/rounds/{roundId}/qualify` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Qualify application |
| 43 | POST | `/applications/{applicationId}/rounds/{roundId}/disqualify` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Disqualify application |
| 44 | POST | `/applications/{applicationId}/rounds/{roundId}/select` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER | Select application |
| 45 | POST | `/applications/{applicationId}/rounds/{roundId}/hold` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Hold application decision |
| 46 | GET | `/rounds/{roundId}/applications/{applicationId}` | CandidateRoundController | MEMBER (own), ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Get round and QueueEntry status |
| 47 | POST | `/rounds/{roundId}/candidate-rounds/{candidateRoundId}/evaluate` | InterviewEvaluationController | assigned INTERVIEWER | Submit final evaluation |
| 48 | GET | `/evaluations/me` | InterviewEvaluationController | INTERVIEWER | List own evaluations |
| 49 | GET | `/evaluations/{id}` | InterviewEvaluationController | ORG_ADMIN, QUEUE_MANAGER, own INTERVIEWER | Get evaluation |
| 50 | GET | `/rounds/{roundId}/evaluations` | InterviewEvaluationController | ORG_ADMIN, QUEUE_MANAGER | List evaluations |
| 51 | POST | `/rounds/{roundId}/call-next` | InterviewQueueController | assigned INTERVIEWER | Call next queue entry |
| 52 | POST | `/rounds/{roundId}/start` | InterviewQueueController | ORG_ADMIN, QUEUE_MANAGER | Start round queue |
| 53 | POST | `/rounds/{roundId}/stop` | InterviewQueueController | ORG_ADMIN, QUEUE_MANAGER | Stop round queue |
| 54 | POST | `/rounds/{roundId}/candidate-rounds/{candidateRoundId}/start` | InterviewQueueController | assigned INTERVIEWER | Start interview |
| 55 | POST | `/rounds/{roundId}/candidate-rounds/{candidateRoundId}/complete` | InterviewQueueController | assigned INTERVIEWER | Complete interview |
| 56 | POST | `/rounds/{roundId}/candidate-rounds/{candidateRoundId}/no-show` | InterviewQueueController | assigned INTERVIEWER | Mark no-show |
| 57 | POST | `/rounds/{roundId}/candidate-rounds/{candidateRoundId}/requeue` | InterviewQueueController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Requeue entry |
| 58 | POST | `/rounds/{roundId}/candidate-rounds/{candidateRoundId}/skip` | InterviewQueueController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Skip entry |
| 59 | GET | `/drives/{driveId}/statistics` | StatisticsController | ORG_ADMIN, QUEUE_MANAGER | Drive statistics |
| 60 | GET | `/rounds/{roundId}/statistics` | StatisticsController | ORG_ADMIN, QUEUE_MANAGER | Round statistics |
| 61 | GET | `/drives/{driveId}/rounds/{roundId}/candidates` | StatisticsController | ORG_ADMIN, QUEUE_MANAGER | Candidate list |
| 62 | GET | `/drives/{driveId}/statistics/summary` | StatisticsController | ORG_ADMIN, QUEUE_MANAGER | Summary |
| 63 | POST | `/applications/{applicationId}/rounds/{roundId}/progress` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER | Register a qualified candidate in the next existing round |

### API response rule

Candidate-round responses include `queueEntryId` and the current queue status
read from `QueueEntry`. They may include interview result and decision fields,
but never a persisted CandidateRound queue status. Queue mutations return the
updated `QueueEntry` view and the associated interview projection together.

`applicationId` identifies a CandidateApplication and is used where the
candidate-round association is addressed through its application and round.
`candidateRoundId` identifies the concrete CandidateRound and is used for
queue mutations and evaluation submission. No endpoint uses the ambiguous
`candidateId` parameter.

### Evaluation revision decision

Phase 3 removes the evaluation update endpoint and does not publish
`EVALUATION_UPDATED`. `POST .../evaluate` is one-time and final. A future
audited correction workflow would require authorization, immutable history,
reason capture, and an explicit event; it is not part of this milestone.

## 9. Outbox events and compatibility

The existing QMS `QueueEventType` enum defines **17 queue audit event names**:
`QUEUE_CREATED`, `QUEUE_UPDATED`, `QUEUE_OPENED`, `QUEUE_PAUSED`,
`QUEUE_RESUMED`, `QUEUE_CLOSED`, `QUEUE_ARCHIVED`, `ENTRY_REGISTERED`,
`ENTRY_CALLED`, `ENTRY_STARTED`, `ENTRY_COMPLETED`, `ENTRY_CANCELLED`,
`ENTRY_NO_SHOW`, `ENTRY_SKIPPED`, `ENTRY_REQUEUED`, `ASSIGNMENT_CREATED`, and
`ASSIGNMENT_RELEASED`. The inspected QueueService emits 15 of those audit
events; `QUEUE_ARCHIVED` and `ASSIGNMENT_RELEASED` are defined but have no
observed emission call in the inspected service.

The existing QueueService creates **9 outbox event names**:
`QUEUE_ENTRY_REGISTERED`, `QUEUE_ENTRY_CALLED`, `QUEUE_ASSIGNMENT_CREATED`,
`QUEUE_ENTRY_STARTED`, `QUEUE_ENTRY_COMPLETED`, `QUEUE_ENTRY_NO_SHOW`,
`QUEUE_ENTRY_CANCELLED`, `QUEUE_ENTRY_REQUEUED`, and `QUEUE_ENTRY_SKIPPED`.
It persists those outbox rows in the same `@Transactional` queue mutation.

Phase 3 adds **24 new interview-domain event names**, listed individually:

1. `DRIVE_CREATED`
2. `DRIVE_PUBLISHED`
3. `DRIVE_REGISTRATION_OPENED`
4. `DRIVE_REGISTRATION_CLOSED`
5. `DRIVE_STARTED`
6. `DRIVE_COMPLETED`
7. `DRIVE_CANCELLED`
8. `APPLICATION_SUBMITTED`
9. `APPLICATION_ELIGIBILITY_UPDATED`
10. `APPLICATION_ACCEPTED`
11. `APPLICATION_REJECTED`
12. `APPLICATION_WITHDRAWN`
13. `ROUND_CREATED`
14. `ROUND_OPENED`
15. `ROUND_PAUSED`
16. `ROUND_RESUMED`
17. `ROUND_CLOSED`
18. `ASSIGNMENT_UPDATED`
19. `CANDIDATE_ROUND_REGISTERED`
20. `CANDIDATE_ROUND_QUALIFIED`
21. `CANDIDATE_ROUND_DISQUALIFIED`
22. `CANDIDATE_ROUND_SELECTED`
23. `CANDIDATE_ROUND_HELD`
24. `EVALUATION_SUBMITTED`

The mapping is one-way and avoids duplicate transitions:

| QueueService event | Interview meaning | Phase 3 action |
|---|---|---|
| `QUEUE_CREATED` | Round queue provisioned | Reference existing event; do not emit a second queue-created event. |
| `QUEUE_OPENED`, `QUEUE_PAUSED`, `QUEUE_RESUMED`, `QUEUE_CLOSED` | Round queue lifecycle | Reference the existing event; optionally emit the corresponding round event once if the round aggregate changes. |
| `ENTRY_REGISTERED` | CandidateRound receives a QueueEntry | Emit `CANDIDATE_ROUND_REGISTERED` for the interview association; do not emit another entry event. |
| `ENTRY_CALLED`, `ENTRY_STARTED`, `ENTRY_COMPLETED`, `ENTRY_NO_SHOW`, `ENTRY_SKIPPED`, `ENTRY_REQUEUED`, `ENTRY_CANCELLED` | Queue lifecycle for the associated CandidateRound | Use the existing queue event as authoritative; do not publish an interview duplicate. |
| `ASSIGNMENT_CREATED`, `ASSIGNMENT_RELEASED` | Queue operator assignment | Use existing queue events; `ASSIGNMENT_UPDATED` is only for interviewer-assignment changes. |

Across the combined outbox design there are **33 distinct event names**: 9
existing QMS outbox events plus 24 new Phase 3 events. The 17-name audit enum
is a separate count and is not added to the outbox count. `EVALUATION_UPDATED`
is absent.

## 10. Lifecycle and workflows

### Drive and application

```text
DRAFT -> PUBLISHED -> REGISTRATION_OPEN -> REGISTRATION_CLOSED
      -> STARTED -> COMPLETED
      \-> CANCELLED

Eligibility: PENDING -> ELIGIBLE
                       \-> INELIGIBLE

Application: SUBMITTED -> ACCEPTED -> WITHDRAWN
                       \-> REJECTED
             Applications with eligibility status ELIGIBLE or INELIGIBLE may
             be rejected while their application status is SUBMITTED;
             SUBMITTED, ELIGIBLE, or ACCEPTED may be withdrawn.
```

Acceptance requires independent `eligibilityStatus = ELIGIBLE`; an ineligible
application cannot be accepted. `ACCEPTED -> REJECTED` and all transitions
from `WITHDRAWN` or `REJECTED` are invalid. Acceptance reserves capacity;
withdrawal does not release that reservation.

### Round and queue

```text
create QueueConfiguration
        |
        v
create DRAFT InterviewRound
        |
        v
configure assignments and candidates; validate readiness
        |
        v
DRAFT -> OPEN -> PAUSED -> OPEN -> CLOSED
                  |
                  +-- QueueEntry.status is read from QueueEntry only
```

`READY` is a derived validation condition, not a persisted round state and is
not an API endpoint. A DRAFT round may transition to OPEN only when its
queue configuration exists and belongs to the organization, the round number
is positive and unique within the drive, the schedule is valid,
`qualificationCriteria` is present, the queue configuration has complete
required settings and can be opened through the existing QueueService
workflow, and the parent drive is in a state that permits the round to open.
Candidate registration is not required for readiness; candidates are added
after the round opens. The open operation performs these checks in one
transaction; failure leaves the round DRAFT.

Round actions do not copy queue status into CandidateRound. Queue actions call
QueueService and persist any interview result/decision separately.

### Candidate registration

The coordinator validates the accepted application and round, invokes
`QueueService.registerParticipant`, then creates the one-to-one CandidateRound
association in the same QMS transaction. The CandidateRound and QueueEntry
write, queue audit event, outbox event, and token sequence roll back together
if any part fails. PostgreSQL integration tests verify this rollback. A
duplicate request returns the existing association after organization,
application, and round checks; it does not create another QueueEntry.

### Evaluation

An assigned interviewer submits one evaluation. Submission is immutable in
Phase 3 and may drive a decision transition, but it does not change
`QueueEntry.status` directly.

## 11. Authorization and tenant isolation

Every service receives the authenticated actor and organization ID, calls the
existing tenant authorization path, and uses organization-scoped lookups.
Coordinator-triggered registration requires `ORG_ADMIN` or `QUEUE_MANAGER`.
Interviewers are authorized only for assigned rounds. Students can access only
their own profile, application, and candidate-round data.

Composite foreign keys include `organization_id` wherever a relationship
crosses a tenant boundary.

## 12. Transactions and concurrency

- Accept/reject/withdraw operations use an application state transition
  guard and lock the `placement_drives` row before counting reservations.
- CandidateRound registration and queue-entry creation are atomic: both
  Spring services use default `REQUIRED` propagation against QMS's single
  PostgreSQL datasource and transaction manager. A PostgreSQL trigger-based
  failure test confirms that queue entry, queue audit, outbox, and token
  sequence writes roll back when CandidateRound persistence fails.
- Registration locks the interview round and then the application. This
  serializes duplicate application/round requests; the database unique
  constraint remains the final duplicate guard. QueueService locks the queue
  configuration for capacity and token allocation.
- Progression locks the destination round before the application, matching
  registration's lock order. Repeated progression returns the existing
  association. QueueEntry status is always read from QueueEntry.
- Interview outcomes are recorded only after the QueueEntry is `COMPLETED`.
  The first outcome is immutable; repeating the same outcome is idempotent,
  while a different later outcome returns `409 Conflict`. Only `QUALIFIED`
  candidates may progress to the next existing round number.
- Queue mutations delegate to QueueService and its existing locking/state
  machine.
- Assignment and candidate-round uniqueness violations are surfaced as
  conflicts, not silently converted to success.
- Outbox records are written in the same transaction as their aggregate
  change and are retried by the existing outbox mechanism.

## 13. Testing plan

Milestone 3.1 defines the schema constraints; it does not add repositories or
tests. Milestone 3.2 database integration tests cover:

1. Composite profile ownership and cross-organization rejection.
2. CGPA, schedule, round-number, and 0-100 evaluation score checks.
3. Application-number uniqueness and NULL-panel uniqueness.
4. CandidateRound application/round uniqueness under concurrent registration.
5. CandidateRound-to-QueueEntry composite foreign-key enforcement.
6. Concurrent acceptance against one `placement_drives` row lock.
7. Capacity reservation retained after withdrawal.
8. Rejection before acceptance not consuming capacity.
9. Capacity reduction rejected below existing reservations.
10. Invalid `ACCEPTED -> REJECTED` transition.
11. Registration rollback and recovery behavior with the QMS transaction
   manager.
12. Idempotent final evaluation submission.
13. Queue event/outbox compatibility without duplicate transition events.

Milestone 3.5 verifies registration authorization, live QueueEntry status
responses, coordinator registration, queue integration, outcome recording,
and progression. Milestones 3.6-3.7 retain evaluation/outbox-domain-event
integration, analytics, operational hardening, and end-to-end verification.

## 14. Milestone boundaries

The overall Phase 3 scope is the database model, business services, REST APIs,
queue integration, immutable evaluations, outbox integration, and analytics
described above. It is intentionally split as follows:

### Milestone 3.1 - Database foundation only

After explicit approval, 3.1 may contain only:

- One new additive Flyway migration for Phase 3; never modify V1 or V2.
- Phase 3 entities and enums.
- Relationships, composite foreign keys, unique constraints, and CHECK
  constraints.
- Schema-level mapping needed by those entities.

Milestone 3.1 does **not** include repositories, database integration tests,
business services, controllers, queue registration, evaluations, outbox
publishing, or analytics.

### Milestone 3.2 - Persistence verification

Add repositories and database integration tests, including PostgreSQL
constraint tests, capacity locking, application-number allocation, and
transaction-manager/rollback verification.

### Remaining milestones

Milestone 3.3 covers business services and state transitions; 3.4 covers REST
APIs and authorization; 3.5 covers CandidateRound lifecycle and QueueService
integration and is implemented below. Milestone 3.6 covers immutable
evaluations and outbox/domain-event integration; 3.7 covers analytics,
operational hardening, and end-to-end verification.

The approved Milestone 3.4 boundary is REST controllers, validated request
DTOs, response views, and JWT actor binding for operations already supported
by the PlacementDrive, CandidateProfile, CandidateApplication, InterviewRound,
and InterviewerAssignment services. These routes use
`/api/v1/organizations/{orgId}` and comprise 36 mappings across five
controllers (17 POST, 13 GET, 3 PATCH, and 3 PUT). Detail/profile replacement
uses PUT; single-field capacity and state transitions use PATCH. The boundary excludes
CandidateRound workflows, QueueService integration, evaluations, statistics,
and unsupported operations such as round deletion/resume and assignment
deletion. Those capabilities remain in their later milestones or require
separate approval.

### Verified milestone status

| Milestone | Status | Evidence |
|---|---|---|
| 3.1 Database foundation | Implemented | V3 migration and Phase 3 entities/enums exist; full PostgreSQL verification passed. |
| 3.2 Persistence verification | Verified | PostgreSQL 15.20 Testcontainers persistence suite passed; Flyway applied V1-V5. |
| 3.3 Business services | Implemented and tested | Phase 3 business services and service tests exist; tests passed in clean verify. |
| 3.4 REST APIs and authorization | Implemented and verified | Five Phase 3 controllers, DTOs, exception responses, and PostgreSQL-backed MockMvc/JWT tests passed. |
| 3.5 CandidateRound lifecycle and queue integration | Implemented and verified | PostgreSQL-backed registration, rollback, concurrency, authorization, outcomes, progression, and live QueueEntry status tests passed. |
| 3.6 Evaluations and outbox integration | Outstanding; not started | No evaluation submission or Phase 3 outbox workflow added. |
| 3.7 Analytics and end-to-end hardening | Outstanding; not started | Statistics and end-to-end features remain outside this task. |

Milestone 3.4 verification used the real JWT filter, TenantService, Phase 3
business services, and PostgreSQL 15.20 through Spring Boot MockMvc tests. Its
11 MVC tests passed with no skips. Its 36 routes are 17 POST, 13 GET, 3 PATCH,
and 3 PUT. The Phase3ControllerTest now contains 13 tests, including
Milestone 3.5 coverage. The complete design inventory is 63 endpoints across
9 controllers, with totals 33 POST, 23 GET, 2 PATCH, 3 PUT, and 2 DELETE.
CandidateRoundController has 9 endpoints; 45 Phase 3 endpoints across six
controllers are implemented.

### Milestone 3.5 implementation record

The following service operations are implemented by `CandidateRoundService`:

- Register an accepted, eligible application in an open interview round.
- Allow first registration only in the first existing round; each subsequent
  registration requires a qualified CandidateRound in the immediately
  preceding existing round.
- Retrieve candidates for a round, a candidate's round progression, and one
  application/round association with current QueueEntry data.
- Record `QUALIFIED`, `DISQUALIFIED`, `SELECTED`, or `HOLD` after the linked
  QueueEntry is `COMPLETED`.
- Progress a qualified application to the next existing interview round,
  using that round's configured Business Queue Engine queue.

Coordinator registration and progression require `ORG_ADMIN` or
`QUEUE_MANAGER`. Outcomes may be recorded by those roles or an interviewer
actively assigned to the round; `SELECTED` is coordinator-only. Candidate
reads are restricted to the application owner, coordinator, or an assigned
interviewer as applicable. All checks use the authenticated actor, active
organization membership, and organization-scoped lookups. Queue status in
responses is mapped from the current `QueueEntry`; CandidateRound stores no
queue status.
Decision authorization is checked before the CandidateRound write lock is
acquired.

| Method | Full path | Controller | Required role | Purpose |
|---|---|---|---|---|
| POST | `/api/v1/organizations/{orgId}/rounds/{roundId}/applications/{applicationId}/register` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER | Register accepted candidate using `QueueService.registerParticipant`. |
| GET | `/api/v1/organizations/{orgId}/rounds/{roundId}/candidates` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | List round candidates with current QueueEntry views. |
| GET | `/api/v1/organizations/{orgId}/applications/{applicationId}/rounds` | CandidateRoundController | own MEMBER, ORG_ADMIN, QUEUE_MANAGER | Retrieve a candidate's interview progression. |
| GET | `/api/v1/organizations/{orgId}/rounds/{roundId}/applications/{applicationId}` | CandidateRoundController | own MEMBER, ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Retrieve one application/round association and live queue state. |
| POST | `/api/v1/organizations/{orgId}/applications/{applicationId}/rounds/{roundId}/qualify` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Record a qualified outcome after queue completion. |
| POST | `/api/v1/organizations/{orgId}/applications/{applicationId}/rounds/{roundId}/disqualify` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Record a disqualified outcome after queue completion. |
| POST | `/api/v1/organizations/{orgId}/applications/{applicationId}/rounds/{roundId}/select` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER | Record a selected outcome after queue completion. |
| POST | `/api/v1/organizations/{orgId}/applications/{applicationId}/rounds/{roundId}/hold` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER, assigned INTERVIEWER | Record a hold outcome after queue completion. |
| POST | `/api/v1/organizations/{orgId}/applications/{applicationId}/rounds/{roundId}/progress` | CandidateRoundController | ORG_ADMIN, QUEUE_MANAGER | Register a qualified candidate in the next existing round. |

`registerParticipant(actor, organizationId, queueId, request)` is invoked
through the existing QueueService Spring proxy. It rechecks active tenant
membership and permission; the CandidateRound workflow does not bypass
`TenantService.active`, create a synthetic principal, or create a queue
configuration. The round's existing queue configuration must already be
open to registrations.

PostgreSQL 15.20 Testcontainers verified that concurrent duplicate requests
return the same CandidateRound and create only one QueueEntry/outbox event.
A drive-row lock serializes registration and progression while the ordered
round list is read, and is shared with round creation and opening. The
immediately preceding CandidateRound is write-locked before the target round,
so simultaneous requests cannot register past a missing or incomplete round.
Progression and direct registration use the same sequential validation.
Repeated and concurrent progression requests return the same next-round
CandidateRound and QueueEntry.
A database trigger injected after QueueService writes verified atomic rollback
of CandidateRound, QueueEntry, queue audit, outbox, and token-sequence state.
The existing queue capacity lock and token concurrency tests also passed.
This milestone emits no new interview-domain event: only QueueService's
existing queue registration audit/outbox events are written. CandidateRound
domain events remain in Milestone 3.6.
No Flyway migration was added or changed.

Verification command:

```text
mvn --batch-mode --no-transfer-progress -pl services/queue-management-service -am clean verify
```

Result: 106 QMS tests and 3 common-module tests; 109 total, 0 failures,
0 errors, 0 skipped. The CandidateRound PostgreSQL integration suite ran 10
tests, including first-round registration, missing/unqualified predecessor
rejection, sequential next-round registration, concurrent skip prevention,
repeated/concurrent progression, decision immutability, authorization, and
transaction rollback. The Spring MVC/JWT/tenant suite ran 13 tests, including
candidate-owned reads, cross-candidate and cross-organization denial, and all
four decision routes. These PostgreSQL-backed tests ran with Docker Desktop
and PostgreSQL 15.20.
All five existing Flyway migrations applied successfully in the test
containers.

## 15. Remaining risks

- Later milestone routes still require their approved integration design.
- Continue PostgreSQL concurrency and operational hardening as part of
  Milestone 3.7.
- The outbox publisher must preserve event ordering for a candidate and round.
- A trusted internal mechanism for automated registration is still unresolved;
  coordinator-triggered registration is the only approved Phase 3 model.
- QueueService DTO details may require an adapter, but the verified method
  methods and authorization behavior must not be bypassed; CandidateRound
  registration currently consumes the existing QueueEntryView.
- Decision history is limited to the existing `result`, `decisionReason`,
  `decidedBy`, and `decidedAt` fields. Phase 3.5 does not provide decision
  revisions or an audit history.
- Queue completion remains a Business Queue Engine operation. This milestone
  records interview outcomes after completion but does not add queue mutation
  endpoints or duplicate queue lifecycle logic.

## 16. Version 2.7 readiness assessment

### Corrections made

- Removed persisted CandidateRound queue status duplication.
- Added the composite candidate profile ownership key and foreign key.
- Replaced incorrect NULL uniqueness reasoning with PostgreSQL 15
  `UNIQUE NULLS NOT DISTINCT`.
- Defined `capacity_reserved_at`, retention after withdrawal, and valid
  acceptance transitions.
- Replaced the unresolved system-user design with coordinator-triggered,
  authorized registration through QueueService.
- Corrected queue/round creation order and verified the actual service methods.
- Enforced one-to-one CandidateRound/QueueEntry association.
- Added one CandidateRound per application per interview round with a composite
  unique constraint.
- Removed evaluation `score`, `feedback`, and `evaluatedBy` from CandidateRound;
  detailed evaluation data remains exclusively in InterviewEvaluation.
- Defined positive `registrationCapacity`, drive-row locking for acceptance and
  capacity changes, and rejection of reductions below existing reservations.
- Removed evaluation revision and `EVALUATION_UPDATED` from Phase 3.
- Reconciled the complete API inventory to 62 endpoints across 9 controllers:
  32 POST, 23 GET, 2 PATCH, 3 PUT, and 2 DELETE. ApplicationController retains the
  application-round listing; CandidateRoundController has 7 endpoints.
- Replaced ambiguous `candidateId` parameters with `applicationId` or
  `candidateRoundId`.
- Added the verified CandidateRound composite foreign key to the existing V2
  QueueEntry key.
- Added evaluation uniqueness, score checks, CGPA, schedule, and round-number
  constraints.
- Replaced generic capacity locking with a concrete `placement_drives FOR
  UPDATE` strategy and a concurrency test.
- Documented the verified `REQUIRED` transaction annotations without claiming
  cross-service atomicity until the transaction manager is tested.
- Reconciled 17 existing audit event names, 9 existing outbox event names, and
  24 new Phase 3 outbox event names without duplicate queue transitions.
- Separated Milestone 3.1 database foundation from 3.2 persistence testing and
  the later implementation milestones.
- Removed the duplicate `GET /applications/{applicationId}/rounds` mapping;
  the ApplicationController endpoint is the sole application-round listing
  route.
- Added tenant-scoped CandidateRound foreign keys to the application, round,
  drive, and QueueEntry, plus same-drive composite relationship keys.
- Defined independent eligibility and application state machines.
- Defined READY as a derived round-readiness condition before DRAFT -> OPEN.
- Added an explicit eligibility/application transition matrix and terminal-state
  rules.
- Added same-queue CandidateRound integrity using `queueConfigurationId` and
  the required additive QueueEntry composite key.
- Clarified CandidateRound/QueueEntry cardinality: CandidateRound-to-entry is
  one-to-one, while unrelated QMS QueueEntries may exist.
- Verified and documented organization-scoped membership foreign keys for
  profiles, interviewer assignments, and evaluations.
- Implemented the five Milestone 3.4 controllers and scoped request/response
  DTOs under the QMS tenant API path.
- Changed full drive, candidate-profile, and interview-round replacements to
  PUT; retained PATCH for capacity and explicit eligibility/assignment
  transitions.
- Added request-level date/schedule ordering validation, DTO size limits, and
  consistent sanitized ApiResponse handling for validation and unexpected
  request errors.
- Enforced documented role boundaries at Phase 3 API routes through the
  existing Phase3Access/TenantService membership checks.
- Added PostgreSQL-backed MockMvc tests for all five controllers, including
  JWT authentication, validation, ownership, tenant isolation, and role access.
- Implemented CandidateRound registration, retrieval, completed-round outcomes,
  and qualified progression using the existing QueueService.
- Verified that QueueService registration and CandidateRound persistence share
  the QMS transaction by forcing a PostgreSQL CandidateRound insert failure and
  asserting rollback of queue, audit, outbox, and token-sequence writes.
- Added PostgreSQL concurrency coverage proving repeated concurrent
  application/round registration creates one CandidateRound and QueueEntry.
- Added the nine CandidateRoundController routes and updated the design
  inventory to 63 endpoints (33 POST, 23 GET, 2 PATCH, 3 PUT, 2 DELETE).
- Ran the required clean QMS reactor verification: 99 total tests across QMS
  and common, with no failures, errors, or skips; PostgreSQL 15.20 Testcontainers
  and Flyway V1-V5 passed.

### Decision status

| Area | Status |
|---|---|
| Queue state ownership | Ready: QueueEntry only |
| Profile ownership constraint | Ready: composite unique key and foreign key |
| NULL panel uniqueness | Ready: PostgreSQL 15 NULLS NOT DISTINCT |
| Capacity reservation | Ready: timestamp reservation retained after withdrawal |
| Registration execution | Ready for coordinator-triggered flow; automation deferred |
| CandidateRound lifecycle | Implemented: registration, reads, outcomes, and qualified progression |
| Queue/CandidateRound transaction | Verified atomic on the shared QMS transaction manager |
| Queue configuration order | Ready: create config before round |
| CandidateRound cardinality | Ready: non-null one-to-one FK; unrelated QueueEntries allowed |
| CandidateRound same-queue integrity | Ready after additive Phase 3 QueueEntry key |
| CandidateRound application/round uniqueness | Ready: composite unique constraint |
| Evaluation ownership | Ready: InterviewEvaluation owns submitted details |
| PlacementDrive capacity | Ready: positive registrationCapacity and locked updates |
| Evaluation revisions | Ready: no Phase 3 update |
| Endpoint/event counts | 63 planned Phase 3 endpoints; 33 POST, 23 GET, 2 PATCH, 3 PUT, 2 DELETE; 33 distinct outbox events |
| Milestone 3.1 scope | Ready: migration, entities, enums, relationships, constraints only |
| Endpoint uniqueness | Ready: 63 unique method/path combinations |
| Milestone 3.5 status | Implemented and verified; Milestones 3.6-3.7 remain outstanding |

### Constraint and domain-field recount

- CandidateRound: 3 unique constraints, 5 explicit composite foreign keys
  (application, round, drive, queue configuration, and same-queue QueueEntry),
  and `queueEntryId` remains non-null.
- InterviewEvaluation: 2 unique constraints, 2 explicit composite foreign
  keys (CandidateRound and interviewer membership), plus 4 score-range checks.
- Candidate/application/round domain checks: 1 CGPA check, 1 positive
  round-number check, 1 schedule-order check, and 1 positive
  `registration_capacity` check. The affected relationship-key recount is:
  PlacementDrive 1 key, CandidateApplication 2 keys, InterviewRound 3 keys,
  InterviewerAssignment 1 key, CandidateRound 3 keys, and
  InterviewEvaluation 2 keys. QueueEntry retains its V2 `(organization_id, id)`
  key and receives one additive `(organization_id, id, queue_configuration_id)`
  key.
- CandidateRound now has 9 listed fields and InterviewEvaluation retains all
  detailed score and feedback fields; no evaluation projection fields remain
  on CandidateRound.

### Decisions verified from source code

- V2 creates `uq_queue_entry_org_id UNIQUE (organization_id, id)`.
- V2 does not create a QueueEntry unique key including
  `queue_configuration_id`; the additive Phase 3 key is required for the
  same-queue foreign key.
- V1 creates `uq_membership_org_user UNIQUE (organization_id, auth_user_id)`,
  which is the referenced key for student profiles, interviewer assignments,
  and interviewer evaluations.
- V3 adds the CandidateRound composite foreign keys, including the same-queue
  QueueEntry relationship, and PostgreSQL persistence tests verify them.
- `QueueService.registerParticipant()` and CandidateRound registration are
  `@Transactional` with default propagation. The PostgreSQL trigger-based
  integration test verifies that both use the same QMS transaction manager.
- QueueService defines 17 audit event names, emits 15 of them in the inspected
  service, and persists 9 named outbox event types for queue mutations.
- `QueueService.createQueueConfig()` and `registerParticipant()` are existing
  methods with the signatures cited in Section 2.

### Remaining implementation risks

- Evaluation/outbox domain events and analytics remain in Milestones 3.6-3.7.
- Outcome corrections are deliberately not supported; adding them requires an
  audited revision policy and history.
- PostgreSQL Testcontainers validation requires Docker to be available in the
  execution environment.
- Final API base-path integration with the existing gateway.
- Whether round lifecycle events should be separate domain outbox events or
  projections of existing queue events; duplicate publication is prohibited.

### Version 2.6 change log

- Added the independent eligibility/application transition matrix.
- Added same-queue database integrity using `queueConfigurationId`, composite
  InterviewRound and QueueEntry keys, and an additive Phase 3 QueueEntry key.
- Clarified one CandidateRound per QueueEntry while allowing unrelated QMS
  QueueEntries.
- Added verified membership foreign keys for profiles, assignments, and
  evaluations.
- Kept the 62-endpoint inventory, 33 outbox-event count, and database-only
  Milestone 3.1 / persistence Milestone 3.2 boundaries unchanged.
- Verified from V2 and the QueueEntry entity that the queue column is
  `queue_configuration_id`, not an assumed alternate name.
- Verified the existing QueueEntry-to-QueueConfiguration composite foreign key,
  and documented the additive three-column unique key required for exact
  same-queue CandidateRound enforcement.
- Corrected the lifecycle wording so rejection applies while application
  status is SUBMITTED.

### Version 2.7 change log

- Implemented CandidateRound registration, retrieval, completed-round outcome
  recording, and progression to the next existing round.
- Reused the existing QueueService registration API and proved shared QMS
  transaction rollback with PostgreSQL failure injection.
- Added same-application/round serialization and idempotent retry behavior.
- Added the nine CandidateRoundController routes, updating the inventory to
  63 endpoints: 33 POST, 23 GET, 2 PATCH, 3 PUT, and 2 DELETE.
- Verified Milestone 3.5 with the clean QMS reactor build: 99 tests total,
  zero failures, errors, or skips; PostgreSQL 15.20 and Flyway V1-V5 passed.
- Recorded Milestones 3.6-3.7 as outstanding.
