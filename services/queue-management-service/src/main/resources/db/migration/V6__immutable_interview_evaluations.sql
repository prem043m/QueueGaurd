CREATE INDEX idx_interview_evaluation_interviewer_submitted
  ON interview_evaluations
    (organization_id, interviewer_user_id, submitted_at DESC, id DESC);

CREATE UNIQUE INDEX uq_evaluation_outbox_idempotency
  ON outbox_events(event_type, idempotency_key)
  WHERE event_type = 'EVALUATION_SUBMITTED'
    AND idempotency_key IS NOT NULL;

CREATE FUNCTION reject_interview_evaluation_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'Submitted interview evaluations are immutable'
    USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_interview_evaluation_immutable
  BEFORE UPDATE OR DELETE ON interview_evaluations
  FOR EACH ROW
  EXECUTE FUNCTION reject_interview_evaluation_mutation();
