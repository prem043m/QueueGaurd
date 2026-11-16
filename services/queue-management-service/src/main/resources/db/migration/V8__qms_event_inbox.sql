CREATE TABLE qms_event_inbox (
  id BIGSERIAL PRIMARY KEY,
  consumer_name VARCHAR(100) NOT NULL,
  event_id VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  organization_id BIGINT NOT NULL,
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  processing_status VARCHAR(20) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  processed_at TIMESTAMPTZ,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  CONSTRAINT uq_qms_event_inbox_consumer_event
    UNIQUE (consumer_name, event_id),
  CONSTRAINT fk_qms_event_inbox_organization
    FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT chk_qms_event_inbox_status
    CHECK (processing_status IN
      ('RECEIVED', 'PROCESSING', 'PROCESSED', 'RETRYABLE', 'DEAD_LETTERED')),
  CONSTRAINT chk_qms_event_inbox_attempts
    CHECK (attempt_count >= 0),
  CONSTRAINT chk_qms_event_inbox_processed_at
    CHECK ((processing_status = 'PROCESSED') = (processed_at IS NOT NULL))
);

CREATE INDEX idx_qms_event_inbox_org_received
  ON qms_event_inbox(organization_id, received_at DESC);

CREATE INDEX idx_qms_event_inbox_status_received
  ON qms_event_inbox(processing_status, received_at);
