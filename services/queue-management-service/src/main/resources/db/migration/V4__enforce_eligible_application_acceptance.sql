ALTER TABLE candidate_applications
    ADD CONSTRAINT chk_candidate_application_accepted_eligible
    CHECK (
        application_status <> 'ACCEPTED'
        OR eligibility_status = 'ELIGIBLE'
    );

