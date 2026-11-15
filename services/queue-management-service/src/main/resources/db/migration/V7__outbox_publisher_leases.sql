ALTER TABLE outbox_events
  DROP CONSTRAINT chk_outbox_status;

ALTER TABLE outbox_events
  ADD CONSTRAINT chk_outbox_status
  CHECK (processing_status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'));

ALTER TABLE outbox_events
  ADD COLUMN next_attempt_at TIMESTAMPTZ,
  ADD COLUMN claimed_at TIMESTAMPTZ,
  ADD COLUMN claim_token UUID,
  ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE outbox_events
SET next_attempt_at = created_at,
    updated_at = COALESCE(published_at, created_at);

ALTER TABLE outbox_events
  ALTER COLUMN next_attempt_at SET DEFAULT now(),
  ALTER COLUMN next_attempt_at SET NOT NULL,
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE outbox_events
  ADD CONSTRAINT chk_outbox_claim_state
  CHECK (
    (processing_status = 'PROCESSING'
      AND claimed_at IS NOT NULL AND claim_token IS NOT NULL)
    OR
    (processing_status <> 'PROCESSING'
      AND claimed_at IS NULL AND claim_token IS NULL)
  );

CREATE INDEX idx_outbox_pending_due
  ON outbox_events(next_attempt_at, created_at, id)
  WHERE processing_status = 'PENDING';

CREATE INDEX idx_outbox_processing_lease
  ON outbox_events(claimed_at, created_at, id)
  WHERE processing_status = 'PROCESSING';

CREATE INDEX idx_outbox_aggregate_delivery_order
  ON outbox_events(organization_id, aggregate_type, aggregate_id, created_at, id)
  WHERE processing_status IN ('PENDING', 'PROCESSING');
