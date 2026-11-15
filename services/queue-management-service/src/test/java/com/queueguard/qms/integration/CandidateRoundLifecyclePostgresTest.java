package com.queueguard.qms.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.CandidateRoundResult;
import com.queueguard.qms.dto.Phase3Dtos.CandidateRoundView;
import com.queueguard.qms.service.CandidateRoundService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CandidateRoundLifecyclePostgresTest {
    private static final int TIMEOUT_SECONDS = 30;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_candidate_round_test")
                    .withUsername("qms_test")
                    .withPassword("qms_test");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CandidateRoundService candidateRoundService;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
    }

    @Test
    void concurrentRegistrationIsIdempotentAndCreatesOneQueueEntry() throws Exception {
        Fixture fixture = fixture();
        int threadCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<CandidateRoundView>> registrations = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                registrations.add(executor.submit(() -> {
                    assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    return candidateRoundService.register(
                            fixture.managerId(), fixture.organizationId(),
                            fixture.applicationId(), fixture.roundId(0));
                }));
            }
            start.countDown();
            List<CandidateRoundView> results = new ArrayList<>();
            for (Future<CandidateRoundView> registration : registrations) {
                results.add(registration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().map(CandidateRoundView::id).distinct().count());
            assertEquals(1, results.stream().map(CandidateRoundView::queueEntryId).distinct().count());
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from candidate_rounds where organization_id = ?",
                    Integer.class, fixture.organizationId()));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from queue_entries where organization_id = ?",
                    Integer.class, fixture.organizationId()));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from outbox_events where organization_id = ? "
                            + "and event_type = 'QUEUE_ENTRY_REGISTERED'",
                    Integer.class, fixture.organizationId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void candidateRoundInsertFailureRollsBackQueueEntryAuditAndOutbox() {
        Fixture fixture = fixture();
        jdbc.execute("""
                create function reject_candidate_round_insert() returns trigger
                language plpgsql as $$
                begin
                    raise exception 'injected CandidateRound persistence failure';
                end;
                $$
                """);
        jdbc.execute("""
                create trigger reject_candidate_round_insert
                before insert on candidate_rounds
                for each row execute function reject_candidate_round_insert()
                """);
        try {
            assertThrows(DataAccessException.class, () -> candidateRoundService.register(
                    fixture.managerId(), fixture.organizationId(),
                    fixture.applicationId(), fixture.roundId(0)));

            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from candidate_rounds where organization_id = ?",
                    Integer.class, fixture.organizationId()));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from queue_entries where organization_id = ?",
                    Integer.class, fixture.organizationId()));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from queue_events where organization_id = ?",
                    Integer.class, fixture.organizationId()));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from outbox_events where organization_id = ?",
                    Integer.class, fixture.organizationId()));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from token_sequences where organization_id = ?",
                    Integer.class, fixture.organizationId()));
        } finally {
            jdbc.execute("drop trigger reject_candidate_round_insert on candidate_rounds");
            jdbc.execute("drop function reject_candidate_round_insert()");
        }
    }

    @Test
    void firstExistingRoundCanBeRegistered() {
        Fixture fixture = fixture();

        CandidateRoundView result = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(0));

        assertEquals(fixture.roundId(0), result.interviewRoundId());
        assertEquals(1, countCandidateRounds(fixture));
        assertEquals(1, countQueueEntries(fixture));
    }

    @Test
    void laterRoundRequiresQualifiedImmediatePredecessor() {
        Fixture fixture = fixture();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> candidateRoundService.register(fixture.managerId(),
                        fixture.organizationId(), fixture.applicationId(),
                        fixture.roundId(1)));

        assertEquals("PREVIOUS_ROUND_NOT_QUALIFIED", exception.getCode());
        assertEquals(0, countCandidateRounds(fixture));
        assertEquals(0, countQueueEntries(fixture));
    }

    @Test
    void immediateNextRoundCanBeDirectlyRegisteredAfterQualification() {
        Fixture fixture = fixture();
        CandidateRoundView firstRound = registerAndQualifyFirstRound(fixture);

        CandidateRoundView secondRound = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(1));

        assertEquals(fixture.roundId(1), secondRound.interviewRoundId());
        assertNotEquals(firstRound.queueEntryId(), secondRound.queueEntryId());
        assertEquals(2, countCandidateRounds(fixture));
        assertEquals(2, countQueueEntries(fixture));
    }

    @Test
    void registrationCannotSkipAnUnqualifiedIntermediateRound() {
        Fixture fixture = fixture();
        registerAndQualifyFirstRound(fixture);
        CandidateRoundView secondRound = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(1));
        completeQueueEntry(fixture, secondRound.queueEntryId());
        candidateRoundService.decide(fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(1),
                CandidateRoundResult.DISQUALIFIED, "Did not qualify");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> candidateRoundService.register(fixture.managerId(),
                        fixture.organizationId(), fixture.applicationId(),
                        fixture.roundId(2)));

        assertEquals("PREVIOUS_ROUND_NOT_QUALIFIED", exception.getCode());
        assertEquals(2, countCandidateRounds(fixture));
        assertEquals(2, countQueueEntries(fixture));
    }

    @Test
    void concurrentLaterRoundRegistrationCannotRacePastAnIncompletePreviousRound()
            throws Exception {
        Fixture fixture = fixture();
        registerAndQualifyFirstRound(fixture);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CandidateRoundView> immediateNext = executor.submit(() -> {
                assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                return candidateRoundService.register(fixture.managerId(),
                        fixture.organizationId(), fixture.applicationId(), fixture.roundId(1));
            });
            Future<Boolean> skippedRound = executor.submit(() -> {
                assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                try {
                    candidateRoundService.register(fixture.managerId(),
                            fixture.organizationId(), fixture.applicationId(),
                            fixture.roundId(2));
                    return true;
                } catch (BusinessException exception) {
                    assertEquals("PREVIOUS_ROUND_NOT_QUALIFIED", exception.getCode());
                    return false;
                }
            });
            start.countDown();

            assertEquals(fixture.roundId(1),
                    immediateNext.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).interviewRoundId());
            assertFalse(skippedRound.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, countCandidateRounds(fixture));
        assertEquals(2, countQueueEntries(fixture));
    }

    @Test
    void repeatedAndConcurrentProgressionCreatesOneCandidateRoundAndQueueEntry()
            throws Exception {
        Fixture fixture = fixture();
        registerAndQualifyFirstRound(fixture);

        CandidateRoundView firstProgress = candidateRoundService.progress(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(0));
        CandidateRoundView repeatedProgress = candidateRoundService.progress(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(0));
        assertEquals(firstProgress.id(), repeatedProgress.id());
        assertEquals(firstProgress.queueEntryId(), repeatedProgress.queueEntryId());

        int threadCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<CandidateRoundView>> progressions = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                progressions.add(executor.submit(() -> {
                    assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    return candidateRoundService.progress(
                            fixture.managerId(), fixture.organizationId(),
                            fixture.applicationId(), fixture.roundId(0));
                }));
            }
            start.countDown();
            for (Future<CandidateRoundView> progression : progressions) {
                CandidateRoundView result = progression.get(
                        TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(firstProgress.id(), result.id());
                assertEquals(firstProgress.queueEntryId(), result.queueEntryId());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, countCandidateRounds(fixture));
        assertEquals(2, countQueueEntries(fixture));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from outbox_events where organization_id = ? "
                        + "and event_type = 'QUEUE_ENTRY_REGISTERED'",
                Integer.class, fixture.organizationId()));
    }

    @Test
    void repeatedOutcomeIsIdempotentAndConflictingOutcomeIsRejected() {
        Fixture fixture = fixture();
        CandidateRoundView candidateRound = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(0));
        completeQueueEntry(fixture, candidateRound.queueEntryId());

        CandidateRoundView firstDecision = candidateRoundService.decide(
                fixture.managerId(), fixture.organizationId(), fixture.applicationId(),
                fixture.roundId(0), CandidateRoundResult.QUALIFIED, "Passed");
        CandidateRoundView repeatedDecision = candidateRoundService.decide(
                fixture.managerId(), fixture.organizationId(), fixture.applicationId(),
                fixture.roundId(0), CandidateRoundResult.QUALIFIED, "Changed reason");
        assertEquals(firstDecision.id(), repeatedDecision.id());
        assertEquals("Passed", repeatedDecision.decisionReason());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> candidateRoundService.decide(fixture.managerId(),
                        fixture.organizationId(), fixture.applicationId(),
                        fixture.roundId(0), CandidateRoundResult.DISQUALIFIED, "Revised"));
        assertEquals("CANDIDATE_ROUND_ALREADY_DECIDED", exception.getCode());
        assertEquals("QUALIFIED", jdbc.queryForObject(
                "select result from candidate_rounds where id = ?",
                String.class, candidateRound.id()));
    }

    @Test
    void interviewersMayRecordNonSelectionOutcomesButCannotSelect() {
        Fixture fixture = fixture();
        long interviewerId = fixture.managerId() + 10;
        addMember(fixture.organizationId(), interviewerId, "INTERVIEWER");
        jdbc.update("""
                insert into interviewer_assignments
                    (organization_id, interview_round_id, interviewer_user_id, panel_name,
                     assigned_at, status, created_at, updated_at, version)
                values (?, ?, ?, 'Panel A', now(), 'ACTIVE', now(), now(), 0)
                """, fixture.organizationId(), fixture.roundId(0), interviewerId);

        CandidateRoundView qualified = registerForAdditionalCandidate(fixture);
        completeQueueEntry(fixture, qualified.queueEntryId());
        assertEquals(CandidateRoundResult.QUALIFIED,
                candidateRoundService.decide(interviewerId, fixture.organizationId(),
                        qualifiedApplicationId(fixture, qualified), fixture.roundId(0),
                        CandidateRoundResult.QUALIFIED, null).result());

        long disqualifiedApplicationId = insertAdditionalAcceptedApplication(fixture);
        CandidateRoundView disqualified = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                disqualifiedApplicationId, fixture.roundId(0));
        completeQueueEntry(fixture, disqualified.queueEntryId());
        assertEquals(CandidateRoundResult.DISQUALIFIED,
                candidateRoundService.decide(interviewerId, fixture.organizationId(),
                        disqualifiedApplicationId, fixture.roundId(0),
                        CandidateRoundResult.DISQUALIFIED, null).result());

        long heldApplicationId = insertAdditionalAcceptedApplication(fixture);
        CandidateRoundView held = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                heldApplicationId, fixture.roundId(0));
        completeQueueEntry(fixture, held.queueEntryId());
        assertEquals(CandidateRoundResult.HOLD,
                candidateRoundService.decide(interviewerId, fixture.organizationId(),
                        heldApplicationId, fixture.roundId(0),
                        CandidateRoundResult.HOLD, null).result());

        long selectedApplicationId = insertAdditionalAcceptedApplication(fixture);
        CandidateRoundView selected = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                selectedApplicationId, fixture.roundId(0));
        completeQueueEntry(fixture, selected.queueEntryId());
        assertThrows(ForbiddenException.class,
                () -> candidateRoundService.decide(interviewerId, fixture.organizationId(),
                        selectedApplicationId, fixture.roundId(0),
                        CandidateRoundResult.SELECTED, null));
        assertNull(jdbc.queryForObject(
                "select result from candidate_rounds where id = ?",
                String.class, selected.id()));
        assertEquals(CandidateRoundResult.SELECTED,
                candidateRoundService.decide(fixture.managerId(), fixture.organizationId(),
                        selectedApplicationId, fixture.roundId(0),
                        CandidateRoundResult.SELECTED, null).result());

        long unauthorizedApplicationId = insertAdditionalAcceptedApplication(fixture);
        CandidateRoundView unauthorized = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                unauthorizedApplicationId, fixture.roundId(0));
        completeQueueEntry(fixture, unauthorized.queueEntryId());
        long otherMemberId = interviewerId + 1;
        addMember(fixture.organizationId(), otherMemberId, "MEMBER");
        assertThrows(ForbiddenException.class,
                () -> candidateRoundService.decide(otherMemberId, fixture.organizationId(),
                        unauthorizedApplicationId, fixture.roundId(0),
                        CandidateRoundResult.HOLD, null));
    }

    private Fixture fixture() {
        long organizationId = jdbc.queryForObject("""
                insert into organizations
                    (name, organization_type, status, created_at, updated_at)
                values (?, 'UNIVERSITY', 'ACTIVE', now(), now())
                returning id
                """, Long.class, "candidate-round-" + System.nanoTime());
        long managerId = Math.abs(System.nanoTime());
        long studentId = managerId + 1;
        addMember(organizationId, managerId, "QUEUE_MANAGER");
        addMember(organizationId, studentId, "MEMBER");

        long profileId = jdbc.queryForObject("""
                insert into candidate_profiles
                    (organization_id, user_id, student_name, email,
                     created_at, updated_at, version)
                values (?, ?, 'Test Candidate', 'candidate@example.test', now(), now(), 0)
                returning id
                """, Long.class, organizationId, studentId);
        long driveId = jdbc.queryForObject("""
                insert into placement_drives
                    (organization_id, name, registration_capacity, status, created_by,
                     created_at, updated_at, version)
                values (?, 'Test Drive', 10, 'PUBLISHED', ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, managerId);
        List<Long> roundIds = new ArrayList<>();
        for (int roundNumber = 1; roundNumber <= 3; roundNumber++) {
            long queueId = jdbc.queryForObject("""
                    insert into queue_configurations
                        (organization_id, name, queue_code, queue_type, ordering_policy,
                         status, priority_enabled, created_by, created_at, updated_at, version)
                    values (?, 'Interview queue', ?, 'GENERIC', 'FIFO', 'OPEN', false,
                            ?, now(), now(), 0)
                    returning id
                    """, Long.class, organizationId,
                    "R" + roundNumber + System.nanoTime(), managerId);
            long roundId = jdbc.queryForObject("""
                    insert into interview_rounds
                        (organization_id, placement_drive_id, queue_configuration_id,
                         round_number, name, round_type, scheduled_start_at, scheduled_end_at,
                         status, qualification_criteria, created_by,
                         created_at, updated_at, version)
                    values (?, ?, ?, ?, ?, 'TECHNICAL', ?, ?, 'OPEN',
                            'criteria', ?, now(), now(), 0)
                    returning id
                    """, Long.class, organizationId, driveId, queueId, roundNumber,
                    "Round " + roundNumber,
                    Timestamp.from(Instant.now().plusSeconds(60L * roundNumber)),
                    Timestamp.from(Instant.now().plusSeconds(3600L * roundNumber)),
                    managerId);
            roundIds.add(roundId);
        }
        long applicationId = jdbc.queryForObject("""
                insert into candidate_applications
                    (organization_id, placement_drive_id, application_number,
                     eligibility_status, application_status, applied_at,
                     candidate_profile_id, student_user_id, capacity_reserved_at,
                     created_at, updated_at, version)
                values (?, ?, 1, 'ELIGIBLE', 'ACCEPTED', now(), ?, ?, now(), now(), now(), 0)
                returning id
                """, Long.class, organizationId, driveId, profileId, studentId);
        return new Fixture(organizationId, managerId, applicationId, driveId, roundIds);
    }

    private CandidateRoundView registerAndQualifyFirstRound(Fixture fixture) {
        CandidateRoundView candidateRound = candidateRoundService.register(
                fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(0));
        completeQueueEntry(fixture, candidateRound.queueEntryId());
        candidateRoundService.decide(fixture.managerId(), fixture.organizationId(),
                fixture.applicationId(), fixture.roundId(0),
                CandidateRoundResult.QUALIFIED, "Passed");
        return candidateRound;
    }

    private void completeQueueEntry(Fixture fixture, Long queueEntryId) {
        jdbc.update("update queue_entries set status = 'COMPLETED' "
                        + "where organization_id = ? and id = ?",
                fixture.organizationId(), queueEntryId);
    }

    private long insertAdditionalAcceptedApplication(Fixture fixture) {
        long studentId = fixture.managerId() + jdbc.queryForObject(
                "select count(*) + 1 from candidate_applications "
                        + "where organization_id = ?",
                Long.class, fixture.organizationId());
        addMember(fixture.organizationId(), studentId, "MEMBER");
        long profileId = jdbc.queryForObject("""
                insert into candidate_profiles
                    (organization_id, user_id, student_name, email,
                     created_at, updated_at, version)
                values (?, ?, 'Additional Candidate', ?, now(), now(), 0)
                returning id
                """, Long.class, fixture.organizationId(), studentId,
                "candidate" + studentId + "@example.test");
        return jdbc.queryForObject("""
                insert into candidate_applications
                    (organization_id, placement_drive_id, application_number,
                     eligibility_status, application_status, applied_at,
                     candidate_profile_id, student_user_id, capacity_reserved_at,
                     created_at, updated_at, version)
                values (?, ?, (select coalesce(max(application_number), 0) + 1
                               from candidate_applications where organization_id = ?),
                        'ELIGIBLE', 'ACCEPTED', now(), ?, ?, now(), now(), now(), 0)
                returning id
                """, Long.class, fixture.organizationId(), fixture.driveId(),
                fixture.organizationId(), profileId, studentId);
    }

    private CandidateRoundView registerForAdditionalCandidate(Fixture fixture) {
        long applicationId = insertAdditionalAcceptedApplication(fixture);
        return candidateRoundService.register(fixture.managerId(), fixture.organizationId(),
                applicationId, fixture.roundId(0));
    }

    private long qualifiedApplicationId(Fixture fixture, CandidateRoundView candidateRound) {
        return jdbc.queryForObject(
                "select candidate_application_id from candidate_rounds where id = ?",
                Long.class, candidateRound.id());
    }

    private int countCandidateRounds(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from candidate_rounds "
                + "where organization_id = ?", Integer.class, fixture.organizationId());
    }

    private int countQueueEntries(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from queue_entries "
                + "where organization_id = ?", Integer.class, fixture.organizationId());
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

    private record Fixture(
            long organizationId, long managerId, long applicationId,
            long driveId, List<Long> roundIds) {
        long roundId(int index) {
            return roundIds.get(index);
        }
    }
}
