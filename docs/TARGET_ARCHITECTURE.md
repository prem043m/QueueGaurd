# Target architecture

## Design

Add one deployable `queue-management-service` (QMS). Its bounded context is multi-tenant business queues, appointment slots, assignments and lifecycle. It is justified because it owns transactional domain rules and must evolve independently from generic message delivery. No service is added for each future application: applications are queue types/configurations in QMS.

```
React portals --HTTPS/WebSocket--> API gateway --> auth-service
                                      |           --> QMS (tenant, queues, schedules, interviews)
                                      |           --> existing queue-service --> worker-service
                                      |                                      \-> notification jobs
                                      |           --> monitor/rate-limit services
                                      \--> WebSocket event hub in QMS
```

QMS owns `qms_db`; it never reads another service database. Auth remains identity authority; QMS stores a reference to `auth_user_id`. Queue-service remains the asynchronous transport for notification, reminder, analytics-export and integration work. It must not determine a student's position or panel assignment.

## Reusable platform capabilities

- **Tenant and access:** organizations, membership, role grants and tenant-aware gateway claims/headers.
- **Configurable queues:** FIFO or priority policy, capacity, entry eligibility, service duration, cancellation/no-show policy and notification templates.
- **Scheduling:** appointment windows/slots, resources (panels), availability and conflict checks.
- **Business lifecycle:** `REGISTERED -> WAITING -> CALLED -> IN_SERVICE -> COMPLETED`, with `CANCELLED`, `NO_SHOW`, `SKIPPED` terminal/exception paths.
- **Live updates:** QMS writes an outbox event in the same transaction; an outbox publisher emits queue events to WebSocket subscribers and enqueues notifications.
- **Analytics:** QMS records immutable lifecycle events and produces tenant-scoped aggregates. Monitor-service still owns infrastructure health/load telemetry.

## Student Interview application

An organization admin creates a placement drive and panels, defines capacity/slots and opens registration. A student joins a drive and receives a digital token represented by a queue entry. Assignment chooses an eligible panel with available capacity and smallest projected wait; ties are deterministic. The panel calls the next assigned candidate, starts/completes an interview, or marks no-show. Each transition is validated and produces a live event plus a notification command.

Future hospital/faculty/helpdesk use the same QueueConfiguration, QueueEntry, assignment, slot and notification capabilities; only eligibility fields, forms and policy plug-ins differ.

## Integration rules

1. Gateway validates the JWT then QMS authorizes tenant membership and role for every resource.
2. Every QMS query/mutation is constrained by `organization_id`; resource IDs alone are never trusted.
3. QMS publishes through a transactional outbox; consumers are idempotent using event IDs.
4. WebSocket connections authenticate at connect time and are authorized per tenant/topic. Use topics `org.{id}.queue.{id}` and `org.{id}.drive.{id}`.
5. Existing `/api/queues/**` remains the infrastructure API. New business API lives under `/api/v1/...`; no existing paths change.
