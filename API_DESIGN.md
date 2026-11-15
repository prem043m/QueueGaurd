# API design

All new endpoints are versioned beneath `/api/v1`. Gateway authentication applies; QMS performs organization-resource authorization. `ORG_ADMIN` means membership with that role; `PLATFORM_ADMIN` is restricted to platform operations.

Phase 1 implements organization onboarding, organization retrieval, the current-user
membership list, and organization-admin membership read/create/role/status operations.
Each QMS instance exposes OpenAPI at `/v3/api-docs` and Swagger UI at
`/swagger-ui/index.html`.

| Area | Endpoint | Authorization |
|---|---|---|
| Auth | existing `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`; add refresh/logout later | public/public/authenticated |
| Organizations | `POST /organizations`, `GET /organizations`, `GET/PATCH /organizations/{orgId}` | authenticated; member; ORG_ADMIN |
| Memberships | `GET/POST /organizations/{orgId}/members`, `PATCH /members/{memberId}/roles` | member; ORG_ADMIN |
| Drives | `POST/GET /organizations/{orgId}/interview-drives`, `GET/PATCH /interview-drives/{id}`, `POST .../{id}/open`, `close` | ORG_ADMIN; members can read published drives |
| Panels | `POST/GET /interview-drives/{id}/panels`, `PATCH/DELETE /panels/{id}`, `POST /panels/{id}/members` | ORG_ADMIN |
| Registration | `POST /interview-drives/{id}/registrations`, `GET .../{id}/registrations`, `GET /me/registrations` | eligible student/self; ORG_ADMIN; self |
| Business queues | `POST/GET /queue-configurations`, `GET/PATCH /queue-configurations/{id}`, `GET .../{id}/entries` | ORG_ADMIN; org staff |
| Tokens | `GET /queue-entries/{id}`, `GET /me/queue-entries`, `POST .../{id}/cancel` | entry owner or staff; self; entry owner |
| Panel operations | `POST /panels/{id}/call-next`, `POST /queue-entries/{id}/call`, `start`, `complete`, `no-show`, `release` | assigned interviewer or ORG_ADMIN |
| Notifications | `GET /me/notifications`, `PATCH /notifications/{id}/read`; `POST /notifications/{id}/retry` | owner; ORG_ADMIN |
| Analytics | `GET /analytics/drives/{id}`, `/queues/{id}`, `/panels/{id}` | ORG_ADMIN; interviewer gets assigned-panel scope |
| Monitoring | existing `/monitor/**`, rate-limit/worker administration | PLATFORM_ADMIN only after RBAC implementation |

## Phase 2 - Business Queue Engine API

The following endpoints are implemented in Phase 2 for generic business queue management:

### Queue Configuration
| Method | Endpoint | Description | Authorization |
|---|---|---|---|
| POST | `/api/v1/organizations/{orgId}/queues` | Create a new business queue | ORG_ADMIN, QUEUE_MANAGER |
| GET | `/api/v1/organizations/{orgId}/queues` | List all queues for organization | member |
| GET | `/api/v1/organizations/{orgId}/queues/{queueId}` | Get queue configuration | member |
| PATCH | `/api/v1/organizations/{orgId}/queues/{queueId}` | Update queue configuration | ORG_ADMIN, QUEUE_MANAGER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/open` | Open queue for registrations | ORG_ADMIN, QUEUE_MANAGER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/pause` | Pause queue | ORG_ADMIN, QUEUE_MANAGER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/resume` | Resume paused queue | ORG_ADMIN, QUEUE_MANAGER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/close` | Close queue | ORG_ADMIN, QUEUE_MANAGER |

### Queue Entry Management (Participant Registration)
| Method | Endpoint | Description | Authorization |
|---|---|---|---|
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries` | Register participant, generate token | staff; self |
| GET | `/api/v1/organizations/{orgId}/queues/{queueId}/entries` | List queue entries (paginated) | staff |
| GET | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}` | Get specific entry | staff; owner |
| GET | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/position` | Get entry position in queue | staff; owner |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/cancel` | Cancel entry | owner; staff |

### Operator Operations
| Method | Endpoint | Description | Authorization |
|---|---|---|---|
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/call-next` | Call next eligible participant | ORG_ADMIN, QUEUE_MANAGER, INTERVIEWER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/start` | Start service for called entry | ORG_ADMIN, QUEUE_MANAGER, INTERVIEWER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/complete` | Complete service | ORG_ADMIN, QUEUE_MANAGER, INTERVIEWER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/no-show` | Mark entry as no-show | ORG_ADMIN, QUEUE_MANAGER, INTERVIEWER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/requeue` | Requeue entry | ORG_ADMIN, QUEUE_MANAGER, INTERVIEWER |
| POST | `/api/v1/organizations/{orgId}/queues/{queueId}/entries/{entryId}/skip` | Skip entry | ORG_ADMIN, QUEUE_MANAGER, INTERVIEWER |

### Queue Statistics
| Method | Endpoint | Description | Authorization |
|---|---|---|---|
| GET | `/api/v1/organizations/{orgId}/queues/{queueId}/stats` | Get queue statistics | staff |
| GET | `/api/v1/organizations/{orgId}/queues/{queueId}/current` | Get current queue state | staff |

## HTTP Status Codes

- `200 OK` - Request succeeded
- `201 Created` - Resource created successfully
- `400 Bad Request` - Invalid request body or parameters
- `401 Unauthorized` - Missing or invalid authentication
- `403 Forbidden` - User doesn't have permission
- `404 Not Found` - Resource not found
- `409 Conflict` - Invalid state transition or concurrent modification
- `422 Unprocessable Entity` - Business rule violation

## Request/Response Standards

- All mutations require JWT authentication
- All mutations require `Idempotency-Key` header for idempotency
- Version conflicts return `409` with `ETag`/`If-Match` headers
- All responses use `ApiResponse<T>` envelope
- Pagination uses `page` and `size` parameters
- Dates are in ISO-8601 format

## Idempotency

All state-changing operations support idempotency keys to prevent duplicate processing:
- Call next
- Start service
- Complete service
- Mark no-show
- Cancel entry
- Requeue entry
- Skip entry

Use `Idempotency-Key` HTTP header with a unique client-generated value (e.g., UUID).

## WebSocket

`POST /panels/{id}/call-next` accepts optional `queueConfigurationId`; response includes entry, token, projected position, session and version. Mutations require `Idempotency-Key`; use `If-Match`/version for operator-controlled transitions. Return `409` for invalid lifecycle/concurrent changes, `403` for cross-tenant access, and `422` for eligibility/schedule conflicts.

WebSocket endpoint: `/api/v1/ws` with bearer token during handshake. Server sends `queue.entry.updated`, `queue.position.changed`, `interview.session.updated`, and `notification.created`; payload always includes organization and aggregate IDs. REST remains authoritative after reconnect.
