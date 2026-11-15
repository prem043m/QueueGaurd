-- QueueGuard Phase 2: Business Queue Engine Schema
-- This migration adds the business queue management tables

-- Queue Configuration table
CREATE TABLE queue_configurations (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(1000),
  queue_code VARCHAR(50) NOT NULL,
  queue_type VARCHAR(20) NOT NULL,
  ordering_policy VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  maximum_capacity INTEGER,
  priority_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  estimated_service_duration_seconds INTEGER,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_queue_config_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT uq_queue_config_org_code UNIQUE (organization_id, queue_code)
);

CREATE INDEX idx_queue_config_org ON queue_configurations(organization_id);
CREATE INDEX idx_queue_config_org_status ON queue_configurations(organization_id, status);
CREATE INDEX idx_queue_config_code ON queue_configurations(queue_code);

-- Queue Entry table
CREATE TABLE queue_entries (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  queue_configuration_id BIGINT NOT NULL,
  token_number VARCHAR(50) NOT NULL,
  external_reference VARCHAR(255),
  participant_user_id BIGINT,
  participant_name VARCHAR(255) NOT NULL,
  participant_email VARCHAR(255),
  priority INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  joined_at TIMESTAMPTZ NOT NULL,
  called_at TIMESTAMPTZ,
  service_started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  cancelled_at TIMESTAMPTZ,
  estimated_wait_seconds INTEGER,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_queue_entry_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT fk_queue_entry_config FOREIGN KEY (queue_configuration_id) REFERENCES queue_configurations(id),
  CONSTRAINT uq_queue_entry_token UNIQUE (organization_id, token_number)
);

CREATE INDEX idx_queue_entry_org ON queue_entries(organization_id);
CREATE INDEX idx_queue_entry_config ON queue_entries(queue_configuration_id);
CREATE INDEX idx_queue_entry_org_config_status ON queue_entries(organization_id, queue_configuration_id, status);
CREATE INDEX idx_queue_entry_org_status ON queue_entries(organization_id, status);
CREATE INDEX idx_queue_entry_config_status_priority ON queue_entries(queue_configuration_id, status, priority DESC, joined_at, id);
CREATE INDEX idx_queue_entry_token ON queue_entries(token_number);
CREATE INDEX idx_queue_entry_participant_user ON queue_entries(participant_user_id);
CREATE INDEX idx_queue_entry_joined_at ON queue_entries(joined_at);

-- Queue Assignment table
CREATE TABLE queue_assignments (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  queue_entry_id BIGINT NOT NULL,
  operator_user_id BIGINT NOT NULL,
  service_point VARCHAR(100),
  assigned_at TIMESTAMPTZ NOT NULL,
  released_at TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_queue_assignment_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT fk_queue_assignment_entry FOREIGN KEY (queue_entry_id) REFERENCES queue_entries(id)
);

CREATE INDEX idx_queue_assignment_org ON queue_assignments(organization_id);
CREATE INDEX idx_queue_assignment_entry ON queue_assignments(queue_entry_id);
CREATE INDEX idx_queue_assignment_operator ON queue_assignments(operator_user_id);
CREATE INDEX idx_queue_assignment_status ON queue_assignments(status);
CREATE INDEX idx_queue_assignment_org_entry_status ON queue_assignments(organization_id, queue_entry_id, status);

-- Partial unique index: one active assignment per entry
CREATE UNIQUE INDEX uq_queue_assignment_active_entry ON queue_assignments(queue_entry_id)
  WHERE status IN ('ASSIGNED', 'ACTIVE');

-- Queue Event table (audit trail)
CREATE TABLE queue_events (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  queue_configuration_id BIGINT,
  queue_entry_id BIGINT,
  event_type VARCHAR(30) NOT NULL,
  actor_user_id BIGINT,
  event_payload TEXT,
  occurred_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_queue_event_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT fk_queue_event_config FOREIGN KEY (queue_configuration_id) REFERENCES queue_configurations(id),
  CONSTRAINT fk_queue_event_entry FOREIGN KEY (queue_entry_id) REFERENCES queue_entries(id)
);

CREATE INDEX idx_queue_event_org ON queue_events(organization_id);
CREATE INDEX idx_queue_event_config ON queue_events(queue_configuration_id);
CREATE INDEX idx_queue_event_entry ON queue_events(queue_entry_id);
CREATE INDEX idx_queue_event_type ON queue_events(event_type);
CREATE INDEX idx_queue_event_occurred_at ON queue_events(occurred_at);
CREATE INDEX idx_queue_event_org_config ON queue_events(organization_id, queue_configuration_id);

-- Outbox Event table (transactional outbox pattern)
CREATE TABLE outbox_events (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  idempotency_key VARCHAR(100),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_outbox_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
);

CREATE INDEX idx_outbox_org ON outbox_events(organization_id);
CREATE INDEX idx_outbox_status ON outbox_events(processing_status);
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_created_at ON outbox_events(created_at);
CREATE INDEX idx_outbox_idempotency ON outbox_events(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_outbox_pending ON outbox_events(processing_status, created_at) WHERE processing_status = 'PENDING';

-- ==================== Composite Foreign Keys for Tenant Consistency ====================

-- Add composite foreign key: queue_entries must reference a queue_config that belongs to the same organization
-- First ensure queue_configurations has unique (organization_id, id)
ALTER TABLE queue_configurations ADD CONSTRAINT uq_queue_config_org_id 
    UNIQUE (organization_id, id);

-- Now add composite foreign key from queue_entries to queue_configurations
ALTER TABLE queue_entries ADD CONSTRAINT fk_queue_entry_org_config 
    FOREIGN KEY (organization_id, queue_configuration_id) 
    REFERENCES queue_configurations (organization_id, id);

-- For queue_assignments: must reference a queue_entry that belongs to the same organization
-- First ensure queue_entries has unique (organization_id, id)
ALTER TABLE queue_entries ADD CONSTRAINT uq_queue_entry_org_id 
    UNIQUE (organization_id, id);

-- Now add composite foreign key from queue_assignments to queue_entries
ALTER TABLE queue_assignments ADD CONSTRAINT fk_queue_assignment_org_entry 
    FOREIGN KEY (organization_id, queue_entry_id) 
    REFERENCES queue_entries (organization_id, id);

-- For queue_events: when queue_configuration_id is present, it must reference a config from the same organization
-- Add composite foreign key for queue_configuration
ALTER TABLE queue_events ADD CONSTRAINT fk_queue_event_org_config 
    FOREIGN KEY (organization_id, queue_configuration_id) 
    REFERENCES queue_configurations (organization_id, id) 
    DEFERRABLE INITIALLY DEFERRED;

-- For queue_events: when queue_entry_id is present, it must reference an entry from the same organization
-- Add composite foreign key for queue_entry
ALTER TABLE queue_events ADD CONSTRAINT fk_queue_event_org_entry 
    FOREIGN KEY (organization_id, queue_entry_id) 
    REFERENCES queue_entries (organization_id, id) 
    DEFERRABLE INITIALLY DEFERRED;

-- ==================== Check Constraints ====================

-- Queue Configuration status check
ALTER TABLE queue_configurations ADD CONSTRAINT chk_queue_config_status 
  CHECK (status IN ('DRAFT', 'OPEN', 'PAUSED', 'CLOSED', 'ARCHIVED'));

-- Queue Configuration type check
ALTER TABLE queue_configurations ADD CONSTRAINT chk_queue_config_type 
  CHECK (queue_type IN ('GENERIC', 'COUNTER', 'APPOINTMENT', 'WALK_IN'));

-- Queue Configuration ordering policy check
ALTER TABLE queue_configurations ADD CONSTRAINT chk_queue_config_ordering 
  CHECK (ordering_policy IN ('FIFO', 'PRIORITY_FIFO'));

-- Queue Entry status check
ALTER TABLE queue_entries ADD CONSTRAINT chk_queue_entry_status 
  CHECK (status IN ('WAITING', 'CALLED', 'IN_SERVICE', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'SKIPPED'));

-- Queue Assignment status check
ALTER TABLE queue_assignments ADD CONSTRAINT chk_queue_assignment_status 
  CHECK (status IN ('ASSIGNED', 'ACTIVE', 'RELEASED', 'COMPLETED'));

-- Queue Event type check
ALTER TABLE queue_events ADD CONSTRAINT chk_queue_event_type 
  CHECK (event_type IN ('QUEUE_CREATED', 'QUEUE_UPDATED', 'QUEUE_OPENED', 'QUEUE_PAUSED', 'QUEUE_RESUMED', 
                        'QUEUE_CLOSED', 'QUEUE_ARCHIVED', 'ENTRY_REGISTERED', 'ENTRY_CALLED', 
                        'ENTRY_STARTED', 'ENTRY_COMPLETED', 'ENTRY_CANCELLED', 'ENTRY_NO_SHOW', 
                        'ENTRY_SKIPPED', 'ENTRY_REQUEUED', 'ASSIGNMENT_CREATED', 'ASSIGNMENT_RELEASED'));

-- Outbox Event status check
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status 
  CHECK (processing_status IN ('PENDING', 'PUBLISHED', 'FAILED'));

-- Token Sequence table for atomic token generation
-- Ensures unique token numbers under concurrent registration
CREATE TABLE token_sequences (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  queue_code VARCHAR(50) NOT NULL,
  date_part VARCHAR(8) NOT NULL,
  last_sequence INTEGER NOT NULL DEFAULT 0,
  created_at DATE NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_token_seq_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT uq_token_seq UNIQUE (organization_id, queue_code, date_part)
);

CREATE INDEX idx_token_seq_org ON token_sequences(organization_id);
CREATE INDEX idx_token_seq_org_queue_date ON token_sequences(organization_id, queue_code, date_part);

-- Create trigger to update updated_at timestamp on queue_configurations
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

DROP TRIGGER IF EXISTS trg_queue_config_updated ON queue_configurations;
CREATE TRIGGER trg_queue_config_updated
    BEFORE UPDATE ON queue_configurations
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_queue_entry_updated ON queue_entries;
CREATE TRIGGER trg_queue_entry_updated
    BEFORE UPDATE ON queue_entries
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Note: token_sequences does not have updated_at column, so no trigger needed
