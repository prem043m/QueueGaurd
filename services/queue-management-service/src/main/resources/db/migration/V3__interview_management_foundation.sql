-- QueueGuard Phase 3: interview management database foundation.
-- This migration is additive. V1 and V2 are intentionally unchanged.

ALTER TABLE queue_entries
    ADD CONSTRAINT uq_queue_entry_org_id_config
    UNIQUE (organization_id, id, queue_configuration_id);

CREATE TABLE placement_drives (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(2000),
  registration_capacity INTEGER NOT NULL,
  status VARCHAR(30) NOT NULL,
  registration_opens_at TIMESTAMPTZ,
  registration_closes_at TIMESTAMPTZ,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_placement_drive_org_id UNIQUE (organization_id, id),
  CONSTRAINT fk_placement_drive_org FOREIGN KEY (organization_id)
    REFERENCES organizations(id),
  CONSTRAINT fk_placement_drive_creator FOREIGN KEY
    (organization_id, created_by)
    REFERENCES memberships(organization_id, auth_user_id),
  CONSTRAINT chk_placement_drive_capacity
    CHECK (registration_capacity > 0),
  CONSTRAINT chk_placement_drive_registration_window
    CHECK (registration_closes_at IS NULL
      OR registration_opens_at IS NULL
      OR registration_closes_at > registration_opens_at),
  CONSTRAINT chk_placement_drive_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'REGISTRATION_OPEN',
                      'REGISTRATION_CLOSED', 'STARTED', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_placement_drive_org_status
  ON placement_drives(organization_id, status);

CREATE TABLE candidate_profiles (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  student_name VARCHAR(255) NOT NULL,
  registration_number VARCHAR(100),
  email VARCHAR(320) NOT NULL,
  phone VARCHAR(50),
  department VARCHAR(255),
  graduation_year INTEGER,
  cgpa NUMERIC(4,2),
  resume_reference VARCHAR(1000),
  skills TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_candidate_profile_org_user UNIQUE (organization_id, user_id),
  CONSTRAINT uq_candidate_profile_org_id_user
    UNIQUE (organization_id, id, user_id),
  CONSTRAINT fk_candidate_profile_membership
    FOREIGN KEY (organization_id, user_id)
    REFERENCES memberships(organization_id, auth_user_id),
  CONSTRAINT chk_candidate_profile_cgpa
    CHECK (cgpa IS NULL OR (cgpa >= 0 AND cgpa <= 10))
);

CREATE INDEX idx_candidate_profile_org
  ON candidate_profiles(organization_id);

CREATE TABLE candidate_applications (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  placement_drive_id BIGINT NOT NULL,
  application_number BIGINT NOT NULL,
  eligibility_status VARCHAR(20) NOT NULL,
  eligibility_reason VARCHAR(2000),
  application_status VARCHAR(20) NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL,
  withdrawal_reason VARCHAR(2000),
  candidate_profile_id BIGINT NOT NULL,
  student_user_id BIGINT NOT NULL,
  capacity_reserved_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_candidate_application_org_id
    UNIQUE (organization_id, id),
  CONSTRAINT uq_candidate_application_number
    UNIQUE (organization_id, placement_drive_id, application_number),
  CONSTRAINT uq_candidate_application_profile_drive
    UNIQUE (organization_id, placement_drive_id, candidate_profile_id),
  CONSTRAINT uq_candidate_application_org_drive_id
    UNIQUE (organization_id, id, placement_drive_id),
  CONSTRAINT fk_candidate_application_drive
    FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives(organization_id, id),
  CONSTRAINT fk_candidate_application_profile_user
    FOREIGN KEY (organization_id, candidate_profile_id, student_user_id)
    REFERENCES candidate_profiles(organization_id, id, user_id),
  CONSTRAINT chk_candidate_application_eligibility
    CHECK (eligibility_status IN ('PENDING', 'ELIGIBLE', 'INELIGIBLE')),
  CONSTRAINT chk_candidate_application_status
    CHECK (application_status IN
      ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
  CONSTRAINT chk_candidate_application_capacity_reservation
    CHECK (
      (application_status = 'ACCEPTED' AND capacity_reserved_at IS NOT NULL)
      OR (application_status IN ('SUBMITTED', 'REJECTED')
          AND capacity_reserved_at IS NULL)
      OR application_status = 'WITHDRAWN'
    )
);

CREATE INDEX idx_candidate_application_org_drive
  ON candidate_applications(organization_id, placement_drive_id);

CREATE TABLE interview_rounds (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  placement_drive_id BIGINT NOT NULL,
  queue_configuration_id BIGINT NOT NULL,
  round_number INTEGER NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(2000),
  round_type VARCHAR(30) NOT NULL,
  scheduled_start_at TIMESTAMPTZ NOT NULL,
  scheduled_end_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(20) NOT NULL,
  qualification_criteria TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_interview_round_org_id
    UNIQUE (organization_id, id),
  CONSTRAINT uq_interview_round_number
    UNIQUE (organization_id, placement_drive_id, round_number),
  CONSTRAINT uq_interview_round_drive_queue
    UNIQUE (organization_id, id, placement_drive_id, queue_configuration_id),
  CONSTRAINT fk_interview_round_drive
    FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives(organization_id, id),
  CONSTRAINT fk_interview_round_queue
    FOREIGN KEY (organization_id, queue_configuration_id)
    REFERENCES queue_configurations(organization_id, id),
  CONSTRAINT fk_interview_round_creator
    FOREIGN KEY (organization_id, created_by)
    REFERENCES memberships(organization_id, auth_user_id),
  CONSTRAINT chk_interview_round_number CHECK (round_number > 0),
  CONSTRAINT chk_interview_round_schedule
    CHECK (scheduled_end_at > scheduled_start_at),
  CONSTRAINT chk_interview_round_type
    CHECK (round_type IN
      ('TECHNICAL', 'HR', 'MANAGERIAL', 'GROUP_DISCUSSION', 'OTHER')),
  CONSTRAINT chk_interview_round_status
    CHECK (status IN ('DRAFT', 'OPEN', 'PAUSED', 'CLOSED'))
);

CREATE INDEX idx_interview_round_org_drive
  ON interview_rounds(organization_id, placement_drive_id);

CREATE TABLE interviewer_assignments (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  interview_round_id BIGINT NOT NULL,
  interviewer_user_id BIGINT NOT NULL,
  panel_name VARCHAR(255),
  service_point VARCHAR(100),
  assigned_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_interviewer_assignment_org_id
    UNIQUE (organization_id, id),
  CONSTRAINT uq_interviewer_assignment_panel
    UNIQUE NULLS NOT DISTINCT
      (organization_id, interview_round_id, interviewer_user_id, panel_name),
  CONSTRAINT fk_interviewer_assignment_round
    FOREIGN KEY (organization_id, interview_round_id)
    REFERENCES interview_rounds(organization_id, id),
  CONSTRAINT fk_interviewer_assignment_membership
    FOREIGN KEY (organization_id, interviewer_user_id)
    REFERENCES memberships(organization_id, auth_user_id),
  CONSTRAINT chk_interviewer_assignment_status
    CHECK (status IN ('ASSIGNED', 'ACTIVE', 'RELEASED'))
);

CREATE INDEX idx_interviewer_assignment_round
  ON interviewer_assignments(organization_id, interview_round_id);

CREATE TABLE candidate_rounds (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  candidate_application_id BIGINT NOT NULL,
  interview_round_id BIGINT NOT NULL,
  placement_drive_id BIGINT NOT NULL,
  queue_configuration_id BIGINT NOT NULL,
  queue_entry_id BIGINT NOT NULL,
  result VARCHAR(20),
  decision_reason VARCHAR(2000),
  decided_by BIGINT,
  decided_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_candidate_round_org_id
    UNIQUE (organization_id, id),
  CONSTRAINT uq_candidate_round_application_round
    UNIQUE (organization_id, candidate_application_id, interview_round_id),
  CONSTRAINT uq_candidate_round_queue_entry
    UNIQUE (organization_id, queue_entry_id),
  CONSTRAINT fk_candidate_round_application
    FOREIGN KEY (organization_id, candidate_application_id, placement_drive_id)
    REFERENCES candidate_applications
      (organization_id, id, placement_drive_id),
  CONSTRAINT fk_candidate_round_round
    FOREIGN KEY
      (organization_id, interview_round_id, placement_drive_id,
       queue_configuration_id)
    REFERENCES interview_rounds
      (organization_id, id, placement_drive_id, queue_configuration_id),
  CONSTRAINT fk_candidate_round_drive
    FOREIGN KEY (organization_id, placement_drive_id)
    REFERENCES placement_drives(organization_id, id),
  CONSTRAINT fk_candidate_round_queue_config
    FOREIGN KEY (organization_id, queue_configuration_id)
    REFERENCES queue_configurations(organization_id, id),
  CONSTRAINT fk_candidate_round_queue_entry
    FOREIGN KEY
      (organization_id, queue_entry_id, queue_configuration_id)
    REFERENCES queue_entries
      (organization_id, id, queue_configuration_id),
  CONSTRAINT fk_candidate_round_decider
    FOREIGN KEY (organization_id, decided_by)
    REFERENCES memberships(organization_id, auth_user_id),
  CONSTRAINT chk_candidate_round_result
    CHECK (result IS NULL OR result IN
      ('QUALIFIED', 'DISQUALIFIED', 'SELECTED', 'HOLD'))
);

CREATE INDEX idx_candidate_round_org_round
  ON candidate_rounds(organization_id, interview_round_id);

CREATE TABLE interview_evaluations (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  candidate_round_id BIGINT NOT NULL,
  interviewer_user_id BIGINT NOT NULL,
  technical_score NUMERIC(5,2) NOT NULL,
  communication_score NUMERIC(5,2) NOT NULL,
  problem_solving_score NUMERIC(5,2) NOT NULL,
  overall_score NUMERIC(5,2) NOT NULL,
  recommendation VARCHAR(20) NOT NULL,
  feedback TEXT NOT NULL,
  submitted_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_interview_evaluation_org_id
    UNIQUE (organization_id, id),
  CONSTRAINT uq_interview_evaluation_reviewer
    UNIQUE (organization_id, candidate_round_id, interviewer_user_id),
  CONSTRAINT fk_interview_evaluation_round
    FOREIGN KEY (organization_id, candidate_round_id)
    REFERENCES candidate_rounds(organization_id, id),
  CONSTRAINT fk_interview_evaluation_membership
    FOREIGN KEY (organization_id, interviewer_user_id)
    REFERENCES memberships(organization_id, auth_user_id),
  CONSTRAINT chk_interview_evaluation_technical
    CHECK (technical_score BETWEEN 0 AND 100),
  CONSTRAINT chk_interview_evaluation_communication
    CHECK (communication_score BETWEEN 0 AND 100),
  CONSTRAINT chk_interview_evaluation_problem_solving
    CHECK (problem_solving_score BETWEEN 0 AND 100),
  CONSTRAINT chk_interview_evaluation_overall
    CHECK (overall_score BETWEEN 0 AND 100),
  CONSTRAINT chk_interview_evaluation_recommendation
    CHECK (recommendation IN ('RECOMMEND', 'DO_NOT_RECOMMEND', 'HOLD'))
);

CREATE INDEX idx_interview_evaluation_round
  ON interview_evaluations(organization_id, candidate_round_id);
