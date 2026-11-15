CREATE TABLE application_number_counters (
  organization_id BIGINT NOT NULL,
  placement_drive_id BIGINT NOT NULL,
  last_number BIGINT NOT NULL,
  CONSTRAINT pk_application_number_counters
    PRIMARY KEY (organization_id, placement_drive_id),
  CONSTRAINT fk_application_number_counter_drive
    FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives(organization_id, id),
  CONSTRAINT chk_application_number_counter_positive
    CHECK (last_number > 0)
);

INSERT INTO application_number_counters
    (organization_id, placement_drive_id, last_number)
SELECT organization_id, placement_drive_id, MAX(application_number)
FROM candidate_applications
GROUP BY organization_id, placement_drive_id
HAVING MAX(application_number) > 0;
