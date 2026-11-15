package com.queueguard.qms.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.queueguard.qms.domain.ApplicationStatus;
import com.queueguard.qms.domain.CandidateApplication;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class Phase3PersistencePostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_phase3_test")
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
    @Autowired private PlacementDriveRepository driveRepository;
    @Autowired private CandidateApplicationRepository applicationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
    }

    @Test
    void flywayRunsAllMigrationsAndCreatesPhase3Schema() {
        assertEquals(7, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class));
        for (String table : List.of("placement_drives", "candidate_profiles",
                "candidate_applications", "interview_rounds", "interviewer_assignments",
                "candidate_rounds", "interview_evaluations", "application_number_counters")) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from information_schema.tables "
                            + "where table_schema = current_schema() and table_name = ?",
                    Integer.class, table), table);
        }
        assertConstraintExists("uq_interviewer_assignment_panel");
        assertConstraintExists("chk_candidate_application_accepted_eligible");
        assertConstraintExists("uq_candidate_application_profile_drive");
        assertConstraintExists("chk_outbox_claim_state");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = current_schema() "
                        + "and indexname = 'idx_outbox_aggregate_delivery_order'",
                Integer.class));
        for (String column : List.of(
                "next_attempt_at", "claimed_at", "claim_token", "updated_at")) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from information_schema.columns "
                            + "where table_schema = current_schema() "
                            + "and table_name = 'outbox_events' and column_name = ?",
                    Integer.class, column), column);
        }
        assertConstraintExists("chk_candidate_application_capacity_reservation");
        assertConstraintExists("chk_interview_round_type");
        assertConstraintExists("uq_candidate_round_application_round");
        assertConstraintExists("uq_candidate_round_queue_entry");
        assertConstraintExists("uq_interview_evaluation_reviewer");
        assertConstraintExists("chk_interview_evaluation_technical");
        assertConstraintExists("chk_interview_evaluation_communication");
        assertConstraintExists("chk_interview_evaluation_problem_solving");
        assertConstraintExists("chk_interview_evaluation_overall");
        assertConstraintExists("uq_queue_entry_org_id_config");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'idx_candidate_application_org_drive'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'idx_interview_evaluation_interviewer_submitted'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'uq_evaluation_outbox_idempotency'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pg_trigger
                where tgname = 'trg_interview_evaluation_immutable'
                  and not tgisinternal
                """, Integer.class));
    }

    @Test
    void repositoriesApplyTenantFiltersAndCapacityCountsUseReservationTimestamp() {
        Fixture fixture = fixture(3);
        assertTrue(driveRepository.findByIdAndOrganizationId(
                fixture.driveId(), fixture.organizationId()).isPresent());
        assertTrue(driveRepository.findByIdAndOrganizationId(
                fixture.driveId(), fixture.organizationId() + 1).isEmpty());
        assertEquals(Boolean.TRUE, new TransactionTemplate(transactionManager).execute(status ->
                driveRepository.findByIdAndOrganizationIdForUpdate(
                        fixture.driveId(), fixture.organizationId()).isPresent()));
        assertTrue(applicationRepository.findByIdAndOrganizationId(
                fixture.applicationId(), fixture.organizationId()).isPresent());
        assertTrue(applicationRepository.findByIdAndOrganizationId(
                fixture.applicationId(), fixture.organizationId() + 1).isEmpty());
        assertEquals(0, applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        fixture.organizationId(), fixture.driveId()));

        updateApplication(fixture.applicationId(), "ELIGIBLE", "ACCEPTED", Instant.now());
        assertEquals(1, applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        fixture.organizationId(), fixture.driveId()));
    }

    @Test
    void tenantForeignKeysRejectCrossOrganizationOrMismatchedOwnership() {
        Fixture fixture = fixture(3);
        long otherOrganization = insertOrganization();
        long otherUser = insertMembership(otherOrganization);
        long otherProfile = insertProfile(otherOrganization, otherUser);

        assertSqlState("23503", () -> insertDrive(otherOrganization + 1000, fixture.creatorUserId(), 1));
        assertSqlState("23503", () -> insertDrive(fixture.organizationId(), otherUser, 1));
        assertSqlState("23503", () -> insertProfile(fixture.organizationId(), otherUser));
        long differentProfileOwner = insertMembership(fixture.organizationId());
        long differentOwnerProfile = insertProfile(
                fixture.organizationId(), differentProfileOwner);
        assertSqlState("23503", () -> insertApplication(fixture.organizationId(),
                fixture.driveId(), 99, otherProfile, fixture.creatorUserId(),
                "PENDING", "SUBMITTED", null));
        assertSqlState("23503", () -> insertApplication(fixture.organizationId(),
                fixture.driveId(), 99, differentOwnerProfile, otherUser,
                "PENDING", "SUBMITTED", null));

        assertSqlState("23503", () -> insertAssignment(fixture.organizationId(),
                fixture.roundId(), otherUser, "other-panel"));
        assertSqlState("23503", () -> insertEvaluation(fixture.organizationId(),
                insertCandidateRound(fixture, fixture.applicationId(), fixture.queueEntryId()),
                otherUser, "50.00", "50.00", "50.00", "50.00"));
    }

    @Test
    void uniqueConstraintsRejectDuplicateProfilesApplicationsRoundsAndEvaluations() {
        Fixture fixture = fixture(3);
        assertSqlState("23505", () -> insertProfile(fixture.organizationId(), fixture.creatorUserId()));
        assertSqlState("23505", () -> insertApplication(fixture.organizationId(),
                fixture.driveId(), 2, fixture.profileId(), fixture.creatorUserId(),
                "PENDING", "SUBMITTED", null));

        long secondProfile = insertProfile(fixture.organizationId(),
                insertMembership(fixture.organizationId()));
        assertSqlState("23505", () -> insertApplication(fixture.organizationId(),
                fixture.driveId(), fixture.applicationNumber(), secondProfile,
                fixture.creatorUserId(), "PENDING", "SUBMITTED", null));
        assertSqlState("23505", () -> insertRound(fixture, 1));

        long candidateRoundId = insertCandidateRound(
                fixture, fixture.applicationId(), fixture.queueEntryId());
        insertEvaluation(fixture.organizationId(), candidateRoundId,
                fixture.creatorUserId(), "80.00", "80.00", "80.00", "80.00");
        assertSqlState("23505", () -> insertEvaluation(fixture.organizationId(),
                candidateRoundId, fixture.creatorUserId(), "70.00", "70.00", "70.00", "70.00"));
    }

    @Test
    void nullPanelAssignmentsAreUniqueAndExistingAssignmentCanBeReactivated() {
        Fixture fixture = fixture(3);
        long assignmentId = insertAssignment(fixture.organizationId(),
                fixture.roundId(), fixture.creatorUserId(), null);
        assertSqlState("23505", () -> insertAssignment(fixture.organizationId(),
                fixture.roundId(), fixture.creatorUserId(), null));
        jdbc.update("update interviewer_assignments set status = 'RELEASED' where id = ?",
                assignmentId);
        assertSqlState("23505", () -> insertAssignment(fixture.organizationId(),
                fixture.roundId(), fixture.creatorUserId(), null));
        jdbc.update("update interviewer_assignments set status = 'ASSIGNED' where id = ?",
                assignmentId);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from interviewer_assignments where id = ? and status = 'ASSIGNED'",
                Integer.class, assignmentId));
    }

    @Test
    void databaseChecksEnforceCapacityEligibilityScoreAndDomainRanges() {
        Fixture fixture = fixture(2);
        assertSqlState("23514", () -> insertDrive(fixture.organizationId(),
                fixture.creatorUserId(), 0));
        assertSqlState("23514", () -> insertDriveWithWindow(
                fixture.organizationId(), fixture.creatorUserId(), 2,
                Instant.now().plusSeconds(120), Instant.now().plusSeconds(60)));
        assertSqlState("23514", () -> insertProfileWithCgpa(fixture.organizationId(),
                insertMembership(fixture.organizationId()), "10.01"));
        assertSqlState("23514", () -> insertRoundWithValues(fixture, 0,
                "TECHNICAL", Instant.now(), Instant.now().plusSeconds(60), "DRAFT"));
        assertSqlState("23514", () -> insertRoundWithValues(fixture, 2,
                "NOT_A_ROUND_TYPE", Instant.now(), Instant.now().plusSeconds(60), "DRAFT"));
        assertSqlState("23514", () -> insertRoundWithValues(fixture, 2,
                "TECHNICAL", Instant.now(), Instant.now(), "DRAFT"));

        Instant reservation = Instant.now();
        assertSqlState("23514", () -> updateApplication(fixture.applicationId(),
                "PENDING", "ACCEPTED", reservation));
        assertSqlState("23514", () -> updateApplication(fixture.applicationId(),
                "ELIGIBLE", "ACCEPTED", null));
        assertSqlState("23514", () -> updateApplication(fixture.applicationId(),
                "PENDING", "REJECTED", reservation));
        assertSqlState("23514", () -> updateApplication(fixture.applicationId(),
                "PENDING", "SUBMITTED", reservation));

        long evaluationOwner = insertMembership(fixture.organizationId());
        long evaluationProfile = insertProfile(fixture.organizationId(), evaluationOwner);
        long evaluationApplication = insertApplication(fixture.organizationId(),
                fixture.driveId(), 2, evaluationProfile, evaluationOwner,
                "ELIGIBLE", "SUBMITTED", null);
        long evaluationRound = insertCandidateRound(
                fixture, evaluationApplication, insertQueueEntry(fixture, "SCORE-QUEUE"));
        assertSqlState("23514", () -> insertEvaluation(fixture.organizationId(),
                evaluationRound, fixture.creatorUserId(), "100.01", "50.00", "50.00", "50.00"));
        assertSqlState("23514", () -> insertEvaluation(fixture.organizationId(),
                evaluationRound, fixture.creatorUserId(), "50.00", "-0.01", "50.00", "50.00"));
        assertSqlState("23514", () -> insertEvaluation(fixture.organizationId(),
                evaluationRound, fixture.creatorUserId(), "50.00", "50.00", "100.01", "50.00"));
        assertSqlState("23514", () -> insertEvaluation(fixture.organizationId(),
                evaluationRound, fixture.creatorUserId(), "50.00", "50.00", "50.00", "100.01"));

        updateApplication(fixture.applicationId(), "ELIGIBLE", "ACCEPTED", reservation);
        updateApplication(fixture.applicationId(), "ELIGIBLE", "WITHDRAWN", reservation);
        assertEquals(1, applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        fixture.organizationId(), fixture.driveId()));
        updateApplication(fixture.applicationId(), "PENDING", "SUBMITTED", null);
        updateApplication(fixture.applicationId(), "PENDING", "REJECTED", null);
        updateApplication(fixture.applicationId(), "PENDING", "WITHDRAWN", null);
        assertEquals(0, applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        fixture.organizationId(), fixture.driveId()));
    }

    @Test
    void candidateRoundForeignKeysEnforceSameDriveAndSameQueue() {
        Fixture fixture = fixture(3);
        long otherDrive = insertDrive(fixture.organizationId(), fixture.creatorUserId(), 3);
        long otherQueue = insertQueueConfiguration(fixture.organizationId(),
                fixture.creatorUserId(), "OTHER-Q");
        long otherRound = insertRound(fixture, 2, otherDrive, otherQueue);
        long otherProfileOwner = insertMembership(fixture.organizationId());
        long otherProfile = insertProfile(fixture.organizationId(), otherProfileOwner);
        long otherApplication = insertApplication(fixture.organizationId(), otherDrive, 2,
                otherProfile, otherProfileOwner, "ELIGIBLE", "SUBMITTED", null);

        assertSqlState("23503", () -> insertCandidateRoundWithRefs(fixture,
                fixture.applicationId(), otherRound, fixture.driveId(),
                fixture.queueConfigurationId(), insertQueueEntry(fixture, "BAD-DRIVE")));
        assertSqlState("23503", () -> insertCandidateRoundWithRefs(fixture,
                otherApplication, fixture.roundId(), otherDrive,
                fixture.queueConfigurationId(), insertQueueEntry(fixture, "BAD-ROUND")));
        assertSqlState("23503", () -> insertCandidateRoundWithRefs(fixture,
                fixture.applicationId(), fixture.roundId(), fixture.driveId(),
                otherQueue, insertQueueEntry(fixture, "BAD-QUEUE")));

        long validRoundId = insertCandidateRound(
                fixture, fixture.applicationId(), fixture.queueEntryId());
        assertSqlState("23505", () -> insertCandidateRound(
                fixture, fixture.applicationId(), insertQueueEntry(fixture, "DUP-APP-ROUND")));
        assertSqlState("23505", () -> insertCandidateRound(
                fixture, otherApplication, fixture.queueEntryId()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from candidate_rounds where id = ?", Integer.class, validRoundId));
    }

    @Test
    void capacityLockSerializesConcurrentReservationsAtTheLastSlot() throws Exception {
        Fixture fixture = fixture(1);
        long secondProfileOwner = insertMembership(fixture.organizationId());
        long secondProfile = insertProfile(fixture.organizationId(), secondProfileOwner);
        long secondApplication = insertApplication(fixture.organizationId(),
                fixture.driveId(), 2, secondProfile, secondProfileOwner,
                "ELIGIBLE", "SUBMITTED", null);
        List<Long> applicationIds = List.of(fixture.applicationId(), secondApplication);
        CountDownLatch ready = new CountDownLatch(applicationIds.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(applicationIds.size());
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Long applicationId : applicationIds) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        PlacementDrive lockedDrive = driveRepository
                                .findByIdAndOrganizationIdForUpdate(
                                        fixture.driveId(), fixture.organizationId())
                                .orElseThrow();
                        long reserved = applicationRepository
                                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                                        fixture.organizationId(), fixture.driveId());
                        if (reserved >= lockedDrive.getRegistrationCapacity()) {
                            return false;
                        }
                        CandidateApplication application = applicationRepository
                                .findByIdAndOrganizationId(applicationId, fixture.organizationId())
                                .orElseThrow();
                        application.setEligibilityStatus(
                                com.queueguard.qms.domain.EligibilityStatus.ELIGIBLE);
                        application.setApplicationStatus(ApplicationStatus.ACCEPTED);
                        application.setCapacityReservedAt(Instant.now());
                        applicationRepository.saveAndFlush(application);
                        return true;
                    });
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int acceptedCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    acceptedCount++;
                }
            }
            assertEquals(1, acceptedCount);
            assertEquals(1, applicationRepository
                    .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                            fixture.organizationId(), fixture.driveId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void applicationNumberCounterIsAtomicScopedAndRollsBackWithItsTransaction() throws Exception {
        Fixture fixture = fixture(3);
        int threadCount = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Long>> allocations = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                allocations.add(executor.submit(() -> {
                    assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    return applicationRepository.allocateNextApplicationNumber(
                            fixture.organizationId(), fixture.driveId());
                }));
            }
            start.countDown();
            Set<Long> numbers = new java.util.HashSet<>();
            for (Future<Long> allocation : allocations) {
                numbers.add(allocation.get(20, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(threadCount, numbers.size());
            assertEquals(threadCount, numbers.stream().mapToLong(Long::longValue).max().orElseThrow());
        } finally {
            executor.shutdownNow();
        }

        long otherDrive = insertDrive(fixture.organizationId(), fixture.creatorUserId(), 3);
        assertEquals(1L, applicationRepository.allocateNextApplicationNumber(
                fixture.organizationId(), otherDrive));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            assertEquals(13L, applicationRepository.allocateNextApplicationNumber(
                    fixture.organizationId(), fixture.driveId()));
            throw new IllegalStateException("rollback allocation");
        }));
        assertEquals(13L, applicationRepository.allocateNextApplicationNumber(
                fixture.organizationId(), fixture.driveId()));
    }

    @Test
    void queueEntryAndCandidateRoundWritesRollbackTogetherInQmsTransaction() {
        Fixture fixture = fixture(3);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            long queueEntry = insertQueueEntry(fixture, "ROLLBACK-TOKEN");
            insertCandidateRound(fixture, fixture.applicationId(), queueEntry);
            throw new IllegalStateException("rollback queue and candidate round");
        }));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from queue_entries where organization_id = ? "
                        + "and token_number = 'ROLLBACK-TOKEN'",
                Integer.class, fixture.organizationId()));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from candidate_rounds where organization_id = ?",
                Integer.class, fixture.organizationId()));
    }

    private Fixture fixture(int capacity) {
        long organizationId = insertOrganization();
        long creator = insertMembership(organizationId);
        long driveId = insertDrive(organizationId, creator, capacity);
        long profileId = insertProfile(organizationId, creator);
        long queueId = insertQueueConfiguration(organizationId, creator, "MAIN-Q");
        long queueEntryId = insertQueueEntry(organizationId, queueId, creator, "MAIN-TOKEN");
        long roundId = insertRound(organizationId, driveId, queueId, creator, 1);
        long applicationId = insertApplication(organizationId, driveId, 1, profileId,
                creator, "PENDING", "SUBMITTED", null);
        return new Fixture(organizationId, creator, driveId, profileId, applicationId,
                queueId, queueEntryId, roundId, 1);
    }

    private long insertOrganization() {
        return jdbc.queryForObject("""
                insert into organizations
                    (name, organization_type, status, created_at, updated_at)
                values (?, 'UNIVERSITY', 'ACTIVE', now(), now())
                returning id
                """, Long.class, "org-" + System.nanoTime());
    }

    private long insertMembership(long organizationId) {
        long userId = Math.abs(System.nanoTime());
        jdbc.update("""
                insert into memberships
                    (organization_id, auth_user_id, status, created_at, updated_at)
                values (?, ?, 'ACTIVE', now(), now())
                """, organizationId, userId);
        return userId;
    }

    private long insertDrive(long organizationId, long creatorUserId, int capacity) {
        return jdbc.queryForObject("""
                insert into placement_drives
                    (organization_id, name, registration_capacity, status, created_by,
                     created_at, updated_at, version)
                values (?, ?, ?, 'DRAFT', ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, "drive-" + System.nanoTime(),
                capacity, creatorUserId);
    }

    private long insertDriveWithWindow(long organizationId, long creatorUserId,
            int capacity, Instant opensAt, Instant closesAt) {
        return jdbc.queryForObject("""
                insert into placement_drives
                    (organization_id, name, registration_capacity, status,
                     registration_opens_at, registration_closes_at, created_by,
                     created_at, updated_at, version)
                values (?, ?, ?, 'DRAFT', ?, ?, ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, "window-drive-" + System.nanoTime(),
                capacity, Timestamp.from(opensAt), Timestamp.from(closesAt), creatorUserId);
    }

    private long insertProfile(long organizationId, long userId) {
        return jdbc.queryForObject("""
                insert into candidate_profiles
                    (organization_id, user_id, student_name, email, created_at, updated_at, version)
                values (?, ?, ?, ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, userId, "Student " + userId,
                userId + "@example.test");
    }

    private long insertProfileWithCgpa(long organizationId, long userId, String cgpa) {
        return jdbc.queryForObject("""
                insert into candidate_profiles
                    (organization_id, user_id, student_name, email, cgpa,
                     created_at, updated_at, version)
                values (?, ?, 'Invalid CGPA', 'invalid@example.test', CAST(? AS NUMERIC),
                        now(), now(), 0)
                returning id
                """, Long.class, organizationId, userId, cgpa);
    }

    private long insertApplication(long organizationId, long driveId, long number,
            long profileId, long studentUserId, String eligibility, String status,
            Instant capacityReservedAt) {
        return jdbc.queryForObject("""
                insert into candidate_applications
                    (organization_id, placement_drive_id, application_number,
                     eligibility_status, application_status, applied_at,
                     candidate_profile_id, student_user_id, capacity_reserved_at,
                     created_at, updated_at, version)
                values (?, ?, ?, ?, ?, now(), ?, ?, ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, driveId, number, eligibility,
                status, profileId, studentUserId,
                capacityReservedAt == null ? null : Timestamp.from(capacityReservedAt));
    }

    private void updateApplication(long applicationId, String eligibility,
            String status, Instant capacityReservedAt) {
        jdbc.update("""
                update candidate_applications
                set eligibility_status = ?, application_status = ?, capacity_reserved_at = ?
                where id = ?
                """, eligibility, status,
                capacityReservedAt == null ? null : Timestamp.from(capacityReservedAt),
                applicationId);
    }

    private long insertQueueConfiguration(long organizationId, long creator, String code) {
        return jdbc.queryForObject("""
                insert into queue_configurations
                    (organization_id, name, queue_code, queue_type, ordering_policy,
                     status, priority_enabled, created_by, created_at, updated_at, version)
                values (?, ?, ?, 'GENERIC', 'FIFO', 'OPEN', false, ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, code, code, creator);
    }

    private long insertQueueEntry(Fixture fixture, String token) {
        return insertQueueEntry(fixture.organizationId(), fixture.queueConfigurationId(),
                fixture.creatorUserId(), token);
    }

    private long insertQueueEntry(long organizationId, long queueId, long userId, String token) {
        return jdbc.queryForObject("""
                insert into queue_entries
                    (organization_id, queue_configuration_id, token_number,
                     participant_user_id, participant_name, priority, status,
                     joined_at, created_at, updated_at, version)
                values (?, ?, ?, ?, 'Candidate', 0, 'WAITING', now(), now(), now(), 0)
                returning id
                """, Long.class, organizationId, queueId, token, userId);
    }

    private long insertRound(Fixture fixture, int roundNumber) {
        return insertRound(fixture.organizationId(), fixture.driveId(),
                fixture.queueConfigurationId(), fixture.creatorUserId(), roundNumber);
    }

    private long insertRound(Fixture fixture, int number, long driveId, long queueId) {
        return insertRound(fixture.organizationId(), driveId, queueId,
                fixture.creatorUserId(), number);
    }

    private long insertRound(long organizationId, long driveId, long queueId,
            long creator, int number) {
        return insertRoundWithValues(organizationId, driveId, queueId, creator, number,
                "TECHNICAL", Instant.now(), Instant.now().plusSeconds(3600), "DRAFT");
    }

    private long insertRoundWithValues(Fixture fixture, int number, String type,
            Instant start, Instant end, String status) {
        return insertRoundWithValues(fixture.organizationId(), fixture.driveId(),
                fixture.queueConfigurationId(), fixture.creatorUserId(), number,
                type, start, end, status);
    }

    private long insertRoundWithValues(long organizationId, long driveId, long queueId,
            long creator, int number, String type, Instant start, Instant end, String status) {
        return jdbc.queryForObject("""
                insert into interview_rounds
                    (organization_id, placement_drive_id, queue_configuration_id,
                     round_number, name, round_type, scheduled_start_at, scheduled_end_at,
                     status, qualification_criteria, created_by, created_at, updated_at, version)
                values (?, ?, ?, ?, 'Round ' || ?, ?, ?, ?, ?, 'criteria', ?, now(), now(), 0)
                returning id
                """, Long.class, organizationId, driveId, queueId, number, number,
                type, Timestamp.from(start), Timestamp.from(end), status, creator);
    }

    private long insertAssignment(long organizationId, long roundId,
            long interviewerUserId, String panelName) {
        return jdbc.queryForObject("""
                insert into interviewer_assignments
                    (organization_id, interview_round_id, interviewer_user_id, panel_name,
                     assigned_at, status, created_at, updated_at, version)
                values (?, ?, ?, ?, now(), 'ASSIGNED', now(), now(), 0)
                returning id
                """, Long.class, organizationId, roundId, interviewerUserId, panelName);
    }

    private long insertCandidateRound(Fixture fixture, long applicationId, long queueEntryId) {
        return insertCandidateRoundWithRefs(fixture, applicationId, fixture.roundId(),
                fixture.driveId(), fixture.queueConfigurationId(), queueEntryId);
    }

    private long insertCandidateRoundWithRefs(Fixture fixture, long applicationId,
            long roundId, long driveId, long queueId, long queueEntryId) {
        return jdbc.queryForObject("""
                insert into candidate_rounds
                    (organization_id, candidate_application_id, interview_round_id,
                     placement_drive_id, queue_configuration_id, queue_entry_id,
                     created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, now(), now(), 0)
                returning id
                """, Long.class, fixture.organizationId(), applicationId,
                roundId, driveId, queueId, queueEntryId);
    }

    private long insertEvaluation(long organizationId, long candidateRoundId,
            long interviewerUserId, String technical, String communication,
            String problemSolving, String overall) {
        return jdbc.queryForObject("""
                insert into interview_evaluations
                    (organization_id, candidate_round_id, interviewer_user_id,
                     technical_score, communication_score, problem_solving_score,
                     overall_score, recommendation, feedback, submitted_at,
                     created_at, updated_at, version)
                values (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                        'RECOMMEND', 'feedback', now(), now(), now(), 0)
                returning id
                """, Long.class, organizationId, candidateRoundId, interviewerUserId,
                technical, communication, problemSolving, overall);
    }

    private void assertConstraintExists(String constraintName) {
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from pg_constraint where conname = ?
                """, Integer.class, constraintName), constraintName);
    }

    private void assertSqlState(String expectedState, Runnable statement) {
        DataAccessException exception = assertThrows(DataAccessException.class, statement::run);
        Throwable cause = exception;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(SQLException.class, cause);
        assertEquals(expectedState, ((SQLException) cause).getSQLState());
    }

    private record Fixture(long organizationId, long creatorUserId, long driveId,
            long profileId, long applicationId, long queueConfigurationId,
            long queueEntryId, long roundId, long applicationNumber) {}
}
