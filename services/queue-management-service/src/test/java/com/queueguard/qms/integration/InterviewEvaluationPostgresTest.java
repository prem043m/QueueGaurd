package com.queueguard.qms.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.EvaluationRecommendation;
import com.queueguard.qms.dto.Phase3Dtos.InterviewEvaluationView;
import com.queueguard.qms.dto.Phase3Dtos.SubmitInterviewEvaluationRequest;
import com.queueguard.qms.service.InterviewEvaluationService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InterviewEvaluationPostgresTest {
    private static final int TIMEOUT_SECONDS = 30;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_evaluation_test")
                    .withUsername("qms_test")
                    .withPassword("qms_test");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("queueguard.outbox.enabled", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private InterviewEvaluationService evaluationService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
    }

    @Test
    void submissionPersistsImmutableEvaluationAndStableOutboxEvent() throws Exception {
        Fixture fixture = fixture();

        InterviewEvaluationView evaluation = evaluationService.submit(
                fixture.interviewerId(), fixture.organizationId(), fixture.candidateRoundId(),
                request("81.25"));

        assertEquals(fixture.interviewerId(), evaluation.interviewerUserId());
        assertEquals(fixture.candidateRoundId(), evaluation.candidateRoundId());
        assertNotNull(evaluation.submittedAt());
        assertNotNull(evaluation.createdAt());
        assertNull(jdbc.queryForObject(
                "select result from candidate_rounds where id = ?",
                String.class, fixture.candidateRoundId()));
        assertEquals(1, countEvaluations(fixture));
        assertEquals(1, countEvaluationEvents(fixture));

        String idempotencyKey = jdbc.queryForObject("""
                select idempotency_key from outbox_events
                where organization_id = ? and event_type = 'EVALUATION_SUBMITTED'
                """, String.class, fixture.organizationId());
        assertEquals("evaluation-submitted:" + fixture.organizationId()
                + ":" + evaluation.id(), idempotencyKey);
        assertEquals("PENDING", jdbc.queryForObject("""
                select processing_status from outbox_events
                where idempotency_key = ?
                """, String.class, idempotencyKey));
        assertEquals("InterviewEvaluation", jdbc.queryForObject("""
                select aggregate_type from outbox_events where idempotency_key = ?
                """, String.class, idempotencyKey));
        assertEquals(evaluation.id(), jdbc.queryForObject("""
                select aggregate_id from outbox_events where idempotency_key = ?
                """, Long.class, idempotencyKey));

        JsonNode payload = objectMapper.readTree(jdbc.queryForObject("""
                select payload from outbox_events where idempotency_key = ?
                """, String.class, idempotencyKey));
        assertEquals(fixture.organizationId(), payload.path("organizationId").asLong());
        assertEquals(fixture.candidateRoundId(), payload.path("candidateRoundId").asLong());
        assertEquals(evaluation.id(), payload.path("evaluationId").asLong());
        assertEquals(fixture.interviewerId(), payload.path("interviewerUserId").asLong());

        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> evaluationService.submit(fixture.interviewerId(),
                        fixture.organizationId(), fixture.candidateRoundId(), request("40.00")));
        assertEquals("EVALUATION_ALREADY_SUBMITTED", duplicate.getCode());
        assertSqlState("23505", () -> jdbc.update("""
                insert into outbox_events
                    (organization_id, aggregate_type, aggregate_id, event_type, payload,
                     created_at, processing_status, retry_count, idempotency_key, version)
                values (?, 'InterviewEvaluation', ?, 'EVALUATION_SUBMITTED',
                        '{}', now(), 'PENDING', 0, ?, 0)
                """, fixture.organizationId(), evaluation.id(), idempotencyKey));
        assertEquals(1, countEvaluations(fixture));
        assertEquals(1, countEvaluationEvents(fixture));
    }

    @Test
    void databaseRejectsEvaluationUpdatesAndDeletes() {
        Fixture fixture = fixture();
        InterviewEvaluationView evaluation = evaluationService.submit(
                fixture.interviewerId(), fixture.organizationId(), fixture.candidateRoundId(),
                request("81.25"));

        assertSqlState("23514", () -> jdbc.update(
                "update interview_evaluations set feedback = 'revised' where id = ?",
                evaluation.id()));
        assertSqlState("23514", () -> jdbc.update(
                "delete from interview_evaluations where id = ?", evaluation.id()));
        assertEquals("Structured feedback", jdbc.queryForObject(
                "select feedback from interview_evaluations where id = ?",
                String.class, evaluation.id()));
    }

    @Test
    void interviewerAssignmentAndTenantMembershipAreRequired() {
        Fixture fixture = fixture();
        long unassignedInterviewer = fixture.managerId() + 10;
        addMember(fixture.organizationId(), unassignedInterviewer, "INTERVIEWER");

        assertThrows(ForbiddenException.class,
                () -> evaluationService.submit(unassignedInterviewer,
                        fixture.organizationId(), fixture.candidateRoundId(), request("80.00")));
        assertThrows(ForbiddenException.class,
                () -> evaluationService.submit(fixture.studentId(),
                        fixture.organizationId(), fixture.candidateRoundId(), request("80.00")));

        long otherOrganization = insertOrganization();
        long otherInterviewer = Math.abs(System.nanoTime());
        addMember(otherOrganization, otherInterviewer, "INTERVIEWER");
        assertThrows(NotFoundException.class,
                () -> evaluationService.submit(otherInterviewer, otherOrganization,
                        fixture.candidateRoundId(), request("80.00")));
        assertEquals(0, countEvaluations(fixture));
        assertEquals(0, countEvaluationEvents(fixture));
    }

    @Test
    void assignedInterviewersKeepIndependentEvaluations() {
        Fixture fixture = fixture();
        long secondInterviewer = fixture.managerId() + 10;
        addMember(fixture.organizationId(), secondInterviewer, "INTERVIEWER");
        addAssignment(fixture.organizationId(), fixture.interviewRoundId(), secondInterviewer);

        InterviewEvaluationView first = evaluationService.submit(
                fixture.interviewerId(), fixture.organizationId(), fixture.candidateRoundId(),
                request("81.25"));
        InterviewEvaluationView second = evaluationService.submit(
                secondInterviewer, fixture.organizationId(), fixture.candidateRoundId(),
                request("73.50"));

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.interviewerUserId(), second.interviewerUserId());
        assertEquals(2, countEvaluations(fixture));
        assertEquals(2, countEvaluationEvents(fixture));
    }

    @Test
    void concurrentDuplicateSubmissionsCommitOnlyOneEvaluationAndEvent() throws Exception {
        Fixture fixture = fixture();
        int threadCount = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Boolean>> submissions = new ArrayList<>();
            for (int index = 0; index < threadCount; index++) {
                submissions.add(executor.submit(() -> {
                    assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    try {
                        evaluationService.submit(fixture.interviewerId(),
                                fixture.organizationId(), fixture.candidateRoundId(),
                                request("80.00"));
                        return true;
                    } catch (BusinessException exception) {
                        assertEquals("EVALUATION_ALREADY_SUBMITTED", exception.getCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int successful = 0;
            for (Future<Boolean> submission : submissions) {
                if (submission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    successful++;
                }
            }
            assertEquals(1, successful);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, countEvaluations(fixture));
        assertEquals(1, countEvaluationEvents(fixture));
    }

    @Test
    void evaluationAndOutboxShareCommitVisibilityAndRollback() {
        Fixture committed = fixture();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TransactionTemplate independentRead = new TransactionTemplate(transactionManager);
        independentRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transaction.execute(status -> {
            evaluationService.submit(committed.interviewerId(), committed.organizationId(),
                    committed.candidateRoundId(), request("80.00"));
            Integer visibleEvaluations = independentRead.execute(
                    readStatus -> countEvaluations(committed));
            Integer visibleEvents = independentRead.execute(
                    readStatus -> countEvaluationEvents(committed));
            assertEquals(0, visibleEvaluations);
            assertEquals(0, visibleEvents);
            return null;
        });
        assertEquals(1, countEvaluations(committed));
        assertEquals(1, countEvaluationEvents(committed));

        Fixture rolledBack = fixture();
        transaction.execute(status -> {
            evaluationService.submit(rolledBack.interviewerId(), rolledBack.organizationId(),
                    rolledBack.candidateRoundId(), request("80.00"));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(0, countEvaluations(rolledBack));
        assertEquals(0, countEvaluationEvents(rolledBack));
    }

    @Test
    void outboxInsertFailureRollsBackEvaluation() {
        Fixture fixture = fixture();
        jdbc.execute("""
                create function reject_evaluation_outbox_insert() returns trigger
                language plpgsql as $$
                begin
                    if new.event_type = 'EVALUATION_SUBMITTED' then
                        raise exception 'injected evaluation outbox failure';
                    end if;
                    return new;
                end;
                $$
                """);
        jdbc.execute("""
                create trigger reject_evaluation_outbox_insert
                before insert on outbox_events
                for each row execute function reject_evaluation_outbox_insert()
                """);
        try {
            assertThrows(DataAccessException.class,
                    () -> evaluationService.submit(fixture.interviewerId(),
                            fixture.organizationId(), fixture.candidateRoundId(),
                            request("80.00")));
            assertEquals(0, countEvaluations(fixture));
            assertEquals(0, countEvaluationEvents(fixture));
        } finally {
            jdbc.execute("drop trigger reject_evaluation_outbox_insert on outbox_events");
            jdbc.execute("drop function reject_evaluation_outbox_insert()");
        }
    }

    private Fixture fixture() {
        long organizationId = insertOrganization();
        long managerId = Math.abs(System.nanoTime());
        long interviewerId = managerId + 1;
        long studentId = managerId + 2;
        addMember(organizationId, managerId, "QUEUE_MANAGER");
        addMember(organizationId, interviewerId, "INTERVIEWER");
        addMember(organizationId, studentId, "MEMBER");

        long driveId = jdbc.queryForObject("""
                insert into placement_drives
                    (organization_id, name, registration_capacity, status, created_by,
                     created_at, updated_at, version)
                values (?, 'Evaluation drive', 10, 'PUBLISHED', ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, managerId);
        long profileId = jdbc.queryForObject("""
                insert into candidate_profiles
                    (organization_id, user_id, student_name, email,
                     created_at, updated_at, version)
                values (?, ?, 'Evaluation Candidate', ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, studentId,
                "candidate" + studentId + "@example.test");
        long queueId = jdbc.queryForObject("""
                insert into queue_configurations
                    (organization_id, name, queue_code, queue_type, ordering_policy,
                     status, priority_enabled, created_by, created_at, updated_at, version)
                values (?, 'Evaluation queue', ?, 'GENERIC', 'FIFO', 'OPEN', false,
                        ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, "EV" + System.nanoTime(), managerId);
        long roundId = jdbc.queryForObject("""
                insert into interview_rounds
                    (organization_id, placement_drive_id, queue_configuration_id,
                     round_number, name, round_type, scheduled_start_at, scheduled_end_at,
                     status, qualification_criteria, created_by, created_at, updated_at, version)
                values (?, ?, ?, 1, 'Evaluation Round', 'TECHNICAL', ?, ?, 'OPEN',
                        'criteria', ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, driveId, queueId,
                Timestamp.from(Instant.now().plusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)), managerId);
        long applicationId = jdbc.queryForObject("""
                insert into candidate_applications
                    (organization_id, placement_drive_id, application_number,
                     eligibility_status, application_status, applied_at,
                     candidate_profile_id, student_user_id, created_at, updated_at, version)
                values (?, ?, 1, 'PENDING', 'SUBMITTED', now(), ?, ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, driveId, profileId, studentId);
        long queueEntryId = jdbc.queryForObject("""
                insert into queue_entries
                    (organization_id, queue_configuration_id, token_number,
                     participant_user_id, participant_name, priority, status,
                     joined_at, created_at, updated_at, version)
                values (?, ?, ?, ?, 'Evaluation Candidate', 0, 'WAITING',
                        now(), now(), now(), 0)
                returning id
                """, Long.class, organizationId, queueId,
                "EVAL" + System.nanoTime(), studentId);
        long candidateRoundId = jdbc.queryForObject("""
                insert into candidate_rounds
                    (organization_id, candidate_application_id, interview_round_id,
                     placement_drive_id, queue_configuration_id, queue_entry_id,
                     created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, applicationId, roundId,
                driveId, queueId, queueEntryId);
        addAssignment(organizationId, roundId, interviewerId);
        return new Fixture(organizationId, managerId, interviewerId, studentId,
                roundId, candidateRoundId);
    }

    private long insertOrganization() {
        return jdbc.queryForObject("""
                insert into organizations
                    (name, organization_type, status, created_at, updated_at)
                values (?, 'UNIVERSITY', 'ACTIVE', now(), now())
                returning id
                """, Long.class, "evaluation-org-" + System.nanoTime());
    }

    private void addMember(long organizationId, long userId, String role) {
        long membershipId = jdbc.queryForObject("""
                insert into memberships
                    (organization_id, auth_user_id, status, created_at, updated_at)
                values (?, ?, 'ACTIVE', now(), now())
                returning id
                """, Long.class, organizationId, userId);
        jdbc.update("insert into membership_roles (membership_id, role) values (?, ?)",
                membershipId, role);
    }

    private void addAssignment(long organizationId, long roundId, long interviewerId) {
        jdbc.update("""
                insert into interviewer_assignments
                    (organization_id, interview_round_id, interviewer_user_id, panel_name,
                     assigned_at, status, created_at, updated_at, version)
                values (?, ?, ?, ?, now(), 'ACTIVE', now(), now(), 0)
                """, organizationId, roundId, interviewerId, "Panel " + interviewerId);
    }

    private int countEvaluations(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from interview_evaluations "
                + "where organization_id = ?", Integer.class, fixture.organizationId());
    }

    private int countEvaluationEvents(Fixture fixture) {
        return jdbc.queryForObject("""
                select count(*) from outbox_events
                where organization_id = ? and event_type = 'EVALUATION_SUBMITTED'
                """, Integer.class, fixture.organizationId());
    }

    private void assertSqlState(String expectedState, Runnable statement) {
        DataAccessException exception = assertThrows(DataAccessException.class, statement::run);
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlException) {
                assertEquals(expectedState, sqlException.getSQLState());
                return;
            }
            cause = cause.getCause();
        }
        fail("No SQLException found", exception);
    }

    private static SubmitInterviewEvaluationRequest request(String score) {
        return new SubmitInterviewEvaluationRequest(new BigDecimal(score),
                new BigDecimal("75.00"), new BigDecimal("70.00"),
                new BigDecimal("80.00"), EvaluationRecommendation.RECOMMEND,
                "Structured feedback");
    }

    private record Fixture(
            long organizationId, long managerId, long interviewerId, long studentId,
            long interviewRoundId, long candidateRoundId) {}
}
