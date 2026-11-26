ALTER TABLE outbox_events
  ADD COLUMN traceparent VARCHAR(55),
  ADD COLUMN tracestate VARCHAR(512);
