package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.*;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.dto.QueueDtos.CallNextRequest;
import com.queueguard.qms.dto.QueueDtos.CallNextResponse;
import com.queueguard.qms.dto.QueueDtos.CreateQueueConfigurationRequest;
import com.queueguard.qms.dto.QueueDtos.CreateQueueEntryRequest;
import com.queueguard.qms.dto.QueueDtos.QueueConfigurationView;
import com.queueguard.qms.dto.QueueDtos.QueueEntryView;
import com.queueguard.qms.dto.QueueDtos.StartServiceRequest;
import com.queueguard.qms.repo.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class QueueConcurrencyIntegrationTest {
    private static final int TIMEOUT_SECONDS = 30;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_queue_concurrency_test")
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
    @Autowired private QueueService queueService;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private QueueConfigurationRepository queueConfigurationRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;
    @Autowired private QueueAssignmentRepository queueAssignmentRepository;
    @Autowired private TokenSequenceRepository tokenSequenceRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final AtomicLong userIds = new AtomicLong(1000);

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
        userIds.set(1000);
    }

    @Test
    void tokenGenerationAndCapacityBoundaryRemainSafeUnderConcurrency() throws Exception {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);

        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        jdbc.update("""
                insert into token_sequences
                    (organization_id, queue_code, date_part, last_sequence, created_at, version)
                values (?, 'TOKEN_RACE', ?, 0, current_date, 0)
                """, organizationId, datePart);
        CountDownLatch tokenLocked = new CountDownLatch(1);
        CountDownLatch releaseTokenLock = new CountDownLatch(1);
        ExecutorService tokenExecutor = Executors.newFixedThreadPool(7);
        Future<?> tokenLockHolder = holdTokenSequenceLock(
                tokenExecutor, organizationId, datePart, tokenLocked, releaseTokenLock);
        try {
            assertTrue(tokenLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CountDownLatch tokenStart = new CountDownLatch(1);
            List<Future<String>> tokenFutures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tokenFutures.add(tokenExecutor.submit(() -> {
                    await(tokenStart);
                    return tokenGenerator.generateToken(organizationId, "TOKEN_RACE");
                }));
            }
            tokenStart.countDown();
            awaitDatabaseLockWaiters(2);
            releaseTokenLock.countDown();

            Set<String> tokens = new HashSet<>();
            for (Future<String> future : tokenFutures) {
                assertTrue(tokens.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)));
            }
            assertEquals(12, tokens.size());
            assertEquals(12, jdbc.queryForObject(
                    "select last_sequence from token_sequences "
                            + "where organization_id = ? and queue_code = ?",
                    Integer.class, organizationId, "TOKEN_RACE"));
        } finally {
            releaseTokenLock.countDown();
            try {
                tokenLockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                stop(tokenExecutor);
            }
        }

        long queueId = createOpenQueue(organizationId, managerId, "CAPACITY", 6,
                OrderingPolicy.FIFO, false);
        CountDownLatch queueLocked = new CountDownLatch(1);
        CountDownLatch releaseQueueLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(7);
        Future<?> queueLockHolder = holdQueueConfigurationLock(
                executor, organizationId, queueId, queueLocked, releaseQueueLock);
        try {
            assertTrue(queueLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RegistrationAttempt>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int participant = i;
                futures.add(executor.submit(() -> {
                    await(start);
                    try {
                        return new RegistrationAttempt(register(
                                managerId, organizationId, queueId, "capacity-" + participant), null);
                    } catch (BusinessException exception) {
                        if ("QUEUE_FULL".equals(exception.getCode())) {
                            return new RegistrationAttempt(null, exception.getCode());
                        }
                        throw exception;
                    }
                }));
            }
            start.countDown();
            awaitDatabaseLockWaiters(2);
            releaseQueueLock.countDown();

            List<QueueEntryView> registered = new ArrayList<>();
            int full = 0;
            for (Future<RegistrationAttempt> future : futures) {
                RegistrationAttempt result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (result.entry() != null) {
                    registered.add(result.entry());
                } else {
                    assertEquals("QUEUE_FULL", result.rejectionCode());
                    full++;
                }
            }
            assertEquals(6, registered.size());
            assertEquals(6, full);
            assertEquals(6, queueEntryRepository.countByQueueConfigurationIdAndStatusIn(
                    queueId, List.of(QueueEntryStatus.WAITING, QueueEntryStatus.CALLED,
                            QueueEntryStatus.IN_SERVICE)));
            assertEquals(6, new HashSet<>(registered.stream()
                    .map(QueueEntryView::tokenNumber).toList()).size());
            assertEquals(6, jdbc.queryForObject(
                    "select count(*) from queue_events where queue_configuration_id = ? "
                            + "and event_type = 'ENTRY_REGISTERED'",
                    Integer.class, queueId));
            assertEquals(6, jdbc.queryForObject(
                    "select count(*) from outbox_events where organization_id = ? "
                            + "and event_type = 'QUEUE_ENTRY_REGISTERED'",
                    Integer.class, organizationId));
            assertEquals(6, jdbc.queryForObject(
                    "select last_sequence from token_sequences "
                            + "where organization_id = ? and queue_code = 'CAPACITY'",
                    Integer.class, organizationId));
        } finally {
            releaseQueueLock.countDown();
            try {
                queueLockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                stop(executor);
            }
        }
    }

    @Test
    void concurrentCallNextDoesNotDoubleClaim() throws Exception {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);
        List<Long> interviewerIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            interviewerIds.add(createMember(organizationId, OrganizationRole.INTERVIEWER));
        }

        long concurrentQueueId = createOpenQueue(organizationId, managerId, "CLAIM", 20,
                OrderingPolicy.FIFO, false);
        List<QueueEntryView> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waiting.add(register(managerId, organizationId, concurrentQueueId, "claim-" + i));
        }

        jdbc.execute("""
                CREATE FUNCTION test_hold_called_entry_event() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'ENTRY_CALLED' THEN
                        PERFORM pg_advisory_xact_lock(72101345);
                    END IF;
                    RETURN NEW;
                END;
                $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER test_hold_called_entry_event
                BEFORE INSERT ON queue_events
                FOR EACH ROW EXECUTE FUNCTION test_hold_called_entry_event()
                """);

        ExecutorService executor = Executors.newFixedThreadPool(interviewerIds.size() + 1);
        CountDownLatch advisoryLockHeld = new CountDownLatch(1);
        CountDownLatch releaseAdvisoryLock = new CountDownLatch(1);
        Future<?> advisoryLockHolder = executor.submit(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.execute("select pg_advisory_xact_lock(72101345)");
                    advisoryLockHeld.countDown();
                    await(releaseAdvisoryLock);
                    return null;
                }));
        try {
            assertTrue(advisoryLockHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CallAttempt>> futures = new ArrayList<>();
            for (int i = 0; i < interviewerIds.size(); i++) {
                long interviewerId = interviewerIds.get(i);
                futures.add(executor.submit(() -> {
                    await(start);
                    return callNextAttempt(interviewerId, organizationId, concurrentQueueId,
                            UUID.randomUUID().toString());
                }));
            }
            start.countDown();
            awaitDatabaseLockWaiters(2);
            releaseAdvisoryLock.countDown();

            List<Long> claimedIds = new ArrayList<>();
            int emptyCalls = 0;
            for (Future<CallAttempt> future : futures) {
                CallAttempt result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (result.entryId() != null) {
                    claimedIds.add(result.entryId());
                } else {
                    assertEquals("NO_ENTRIES_AVAILABLE", result.rejectionCode());
                    emptyCalls++;
                }
            }
            assertEquals(5, claimedIds.size());
            assertEquals(5, new HashSet<>(claimedIds).size());
            assertEquals(3, emptyCalls);
            assertEquals(5, queueEntryRepository.countByQueueConfigurationIdAndStatus(
                    concurrentQueueId, QueueEntryStatus.CALLED));
            assertEquals(5, jdbc.queryForObject(
                    "select count(*) from queue_assignments qa "
                            + "join queue_entries qe on qe.id = qa.queue_entry_id "
                            + "where qe.queue_configuration_id = ? and qa.status = 'ASSIGNED'",
                    Integer.class, concurrentQueueId));
        } finally {
            releaseAdvisoryLock.countDown();
            try {
                advisoryLockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS test_hold_called_entry_event ON queue_events");
                jdbc.execute("DROP FUNCTION IF EXISTS test_hold_called_entry_event()");
                stop(executor);
            }

        }

    }

    @Test
    void sequentialCallsHonorFifoAndPriorityOrdering() {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);

        long fifoQueueId = createOpenQueue(organizationId, managerId, "FIFO", 10,
                OrderingPolicy.FIFO, false);
        QueueEntryView fifoFirst = register(managerId, organizationId, fifoQueueId, "fifo-1");
        QueueEntryView fifoSecond = register(managerId, organizationId, fifoQueueId, "fifo-2");
        assertEquals(fifoFirst.id(), queueService.callNext(managerId, organizationId, fifoQueueId,
                new CallNextRequest(null, "fifo-first")).entry().id());
        assertEquals(fifoSecond.id(), queueService.callNext(managerId, organizationId, fifoQueueId,
                new CallNextRequest(null, "fifo-second")).entry().id());

        long priorityQueueId = createOpenQueue(organizationId, managerId, "PRIORITY", 10,
                OrderingPolicy.PRIORITY_FIFO, true);
        QueueEntryView low = register(managerId, organizationId, priorityQueueId, "priority-low", 1);
        QueueEntryView high = register(managerId, organizationId, priorityQueueId, "priority-high", 9);
        QueueEntryView middle = register(managerId, organizationId, priorityQueueId, "priority-middle", 4);
        assertEquals(high.id(), queueService.callNext(managerId, organizationId, priorityQueueId,
                new CallNextRequest(null, "priority-high")).entry().id());
        assertEquals(middle.id(), queueService.callNext(managerId, organizationId, priorityQueueId,
                new CallNextRequest(null, "priority-middle")).entry().id());
        assertEquals(low.id(), queueService.callNext(managerId, organizationId, priorityQueueId,
                new CallNextRequest(null, "priority-low")).entry().id());
    }

    @Test
    void callNextObservesConcurrentQueuePauseBeforeClaiming() throws Exception {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);
        long queueId = createOpenQueue(organizationId, managerId, "PAUSE_RACE", 10,
                OrderingPolicy.FIFO, false);
        QueueEntryView entry = register(managerId, organizationId, queueId, "pause-race");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch pauseFlushed = new CountDownLatch(1);
        CountDownLatch releasePause = new CountDownLatch(1);
        Future<?> pauseTransaction = executor.submit(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    queueService.pauseQueue(managerId, organizationId, queueId);
                    queueConfigurationRepository.flush();
                    pauseFlushed.countDown();
                    await(releasePause);
                    return null;
                }));
        try {
            assertTrue(pauseFlushed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<String> callAttempt = executor.submit(() -> {
                try {
                    queueService.callNext(managerId, organizationId, queueId,
                            new CallNextRequest("Desk", "pause-race-call"));
                    return "CALLED";
                } catch (BusinessException exception) {
                    return exception.getCode();
                }
            });
            awaitDatabaseLockWaiters(1, 5);
            releasePause.countDown();

            assertEquals("QUEUE_PAUSED", callAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(QueueEntryStatus.WAITING,
                    queueEntryRepository.findById(entry.id()).orElseThrow().getStatus());
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from queue_events where queue_entry_id = ? "
                            + "and event_type = 'ENTRY_CALLED'",
                    Integer.class, entry.id()));
        } finally {
            releasePause.countDown();
            try {
                pauseTransaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                stop(executor);
            }
        }
    }

    @Test
    void callNextSkipsHeldEntriesAndReturnsEmptyWhenAllEligibleEntriesAreLocked()
            throws Exception {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);
        long queueId = createOpenQueue(organizationId, managerId, "SKIP_LOCKED", 10,
                OrderingPolicy.FIFO, false);
        List<QueueEntryView> entries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            entries.add(register(managerId, organizationId, queueId, "skip-locked-" + i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch firstLocked = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            Future<?> firstLockHolder = holdQueueEntryLocks(executor, organizationId,
                    List.of(entries.get(0).id()), firstLocked, releaseFirst);
            try {
                assertTrue(firstLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                Future<CallAttempt> skippedCall = executor.submit(() -> callNextAttempt(
                        managerId, organizationId, queueId, "skip-locked-first"));
                CallAttempt result = skippedCall.get(5, TimeUnit.SECONDS);
                assertEquals(entries.get(1).id(), result.entryId());
            } finally {
                releaseFirst.countDown();
                firstLockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            assertEquals(QueueEntryStatus.WAITING,
                    queueEntryRepository.findById(entries.get(0).id()).orElseThrow().getStatus());
            assertEquals(QueueEntryStatus.CALLED,
                    queueEntryRepository.findById(entries.get(1).id()).orElseThrow().getStatus());

            List<Long> remainingWaiting = List.of(
                    entries.get(0).id(), entries.get(2).id(), entries.get(3).id());
            CountDownLatch allLocked = new CountDownLatch(1);
            CountDownLatch releaseAll = new CountDownLatch(1);
            Future<?> allLockHolder = holdQueueEntryLocks(
                    executor, organizationId, remainingWaiting, allLocked, releaseAll);
            try {
                assertTrue(allLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                Future<CallAttempt> noEntries = executor.submit(() -> callNextAttempt(
                        managerId, organizationId, queueId, "skip-locked-none"));
                CallAttempt result = noEntries.get(5, TimeUnit.SECONDS);
                assertNull(result.entryId());
                assertEquals("NO_ENTRIES_AVAILABLE", result.rejectionCode());
            } finally {
                releaseAll.countDown();
                allLockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            assertEquals(3, queueEntryRepository.countByQueueConfigurationIdAndStatus(
                    queueId, QueueEntryStatus.WAITING));
        } finally {
            stop(executor);
        }
    }

    @Test
    void concurrentConfigurationUpdatesUseOptimisticVersioning() throws Exception {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);
        long queueId = createQueue(organizationId, managerId, "VERSION", 10,
                OrderingPolicy.FIFO, false);
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch update = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = updateConfiguration(executor, organizationId, queueId,
                    "writer-one", loaded, update);
            Future<?> second = updateConfiguration(executor, organizationId, queueId,
                    "writer-two", loaded, update);
            assertTrue(loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            update.countDown();

            int committed = 0;
            int stale = 0;
            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    committed++;
                } catch (ExecutionException exception) {
                    if (isOptimisticLockFailure(exception.getCause())) {
                        stale++;
                    } else {
                        throw exception;
                    }
                }
            }
            assertEquals(1, committed);
            assertEquals(1, stale);
            assertTrue(Set.of("writer-one", "writer-two").contains(
                    queueConfigurationRepository.findByOrganizationAndId(organizationId, queueId)
                            .orElseThrow().getDescription()));
        } finally {
            update.countDown();
            stop(executor);
        }
    }

    @Test
    void tenantScopedQueueOperationsCannotCrossOrganizations() throws Exception {
        long organizationA = createOrganization();
        long managerA = createMember(organizationA, OrganizationRole.QUEUE_MANAGER);
        long organizationB = createOrganization();
        long managerB = createMember(organizationB, OrganizationRole.QUEUE_MANAGER);
        long queueB = createOpenQueue(organizationB, managerB, "TENANT_B", 10,
                OrderingPolicy.FIFO, false);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<Boolean>> attacks = List.of(
                    executor.submit(() -> expectNotFound(() -> queueService.registerParticipant(
                            managerA, organizationB, queueB, request("cross-org-membership")))),
                    executor.submit(() -> expectNotFound(() -> queueService.registerParticipant(
                            managerA, organizationA, queueB, request("cross-org-queue")))),
                    executor.submit(() -> expectNotFound(() -> queueService.callNext(
                            managerA, organizationA, queueB,
                            new CallNextRequest(null, "cross-org-call")))));
            for (Future<Boolean> attack : attacks) {
                assertTrue(attack.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            stop(executor);
        }

        assertEquals(0, queueEntryRepository.countByQueueConfigurationIdAndStatus(
                queueB, QueueEntryStatus.WAITING));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from queue_events where queue_configuration_id = ? "
                        + "and event_type = 'ENTRY_REGISTERED'",
                Integer.class, queueB));
    }

    @Test
    void outboxFailureRollsBackEntryAuditAndTokenSequence() {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);
        long queueId = createOpenQueue(organizationId, managerId, "ROLLBACK", 10,
                OrderingPolicy.FIFO, false);

        jdbc.execute("""
                CREATE FUNCTION test_fail_queue_registration_outbox() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'QUEUE_ENTRY_REGISTERED' THEN
                        RAISE EXCEPTION 'TEST_OUTBOX_FAILURE';
                    END IF;
                    RETURN NEW;
                END;
                $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER test_fail_queue_registration_outbox
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION test_fail_queue_registration_outbox()
                """);
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> register(managerId, organizationId, queueId, "rollback-fail"));
            assertTrue(hasMessage(failure, "TEST_OUTBOX_FAILURE"));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_fail_queue_registration_outbox ON outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS test_fail_queue_registration_outbox()");
        }

        assertEquals(0, queueEntryRepository.countByQueueConfigurationIdAndStatus(
                queueId, QueueEntryStatus.WAITING));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from queue_events where queue_configuration_id = ? "
                        + "and event_type = 'ENTRY_REGISTERED'",
                Integer.class, queueId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from outbox_events where organization_id = ? "
                        + "and event_type = 'QUEUE_ENTRY_REGISTERED'",
                Integer.class, organizationId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from token_sequences where organization_id = ? and queue_code = ?",
                Integer.class, organizationId, "ROLLBACK"));

        QueueEntryView successful = register(managerId, organizationId, queueId, "rollback-success");
        assertTrue(successful.tokenNumber().endsWith("000001"));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from queue_events where queue_configuration_id = ? "
                        + "and event_type = 'ENTRY_REGISTERED'",
                Integer.class, queueId));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outbox_events where organization_id = ? "
                        + "and event_type = 'QUEUE_ENTRY_REGISTERED'",
                Integer.class, organizationId));

        jdbc.execute("""
                CREATE FUNCTION test_fail_queue_assignment_outbox() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'QUEUE_ASSIGNMENT_CREATED' THEN
                        RAISE EXCEPTION 'TEST_ASSIGNMENT_OUTBOX_FAILURE';
                    END IF;
                    RETURN NEW;
                END;
                $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER test_fail_queue_assignment_outbox
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION test_fail_queue_assignment_outbox()
                """);
        try {
            RuntimeException callFailure = assertThrows(RuntimeException.class,
                    () -> queueService.callNext(managerId, organizationId, queueId,
                            new CallNextRequest("Desk", "rollback-call")));
            assertTrue(hasMessage(callFailure, "TEST_ASSIGNMENT_OUTBOX_FAILURE"));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_fail_queue_assignment_outbox ON outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS test_fail_queue_assignment_outbox()");
        }

        assertEquals(QueueEntryStatus.WAITING,
                queueEntryRepository.findById(successful.id()).orElseThrow().getStatus());
        assertEquals(0, queueAssignmentRepository.findByQueueEntryIdOrderByAssignedAtDesc(
                successful.id()).size());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from queue_events where queue_entry_id = ? "
                        + "and event_type in ('ENTRY_CALLED', 'ASSIGNMENT_CREATED')",
                Integer.class, successful.id()));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? "
                        + "and event_type in ('QUEUE_ENTRY_CALLED', 'QUEUE_ASSIGNMENT_CREATED')",
                Integer.class, successful.id()));
    }

    @Test
    void competingStateTransitionsCommitAtMostOneTransition() throws Exception {
        long organizationId = createOrganization();
        long managerId = createMember(organizationId, OrganizationRole.QUEUE_MANAGER);
        long queueId = createOpenQueue(organizationId, managerId, "STATE_RACE", 10,
                OrderingPolicy.FIFO, false);
        QueueEntryView entry = register(managerId, organizationId, queueId, "state-race");
        queueService.callNext(managerId, organizationId, queueId,
                new CallNextRequest("Desk", "state-race-call"));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch entryLocked = new CountDownLatch(1);
        CountDownLatch releaseEntryLock = new CountDownLatch(1);
        Future<?> lockHolder = holdQueueEntryLocks(executor, organizationId,
                List.of(entry.id()), entryLocked, releaseEntryLock);
        CountDownLatch start = new CountDownLatch(1);
        try {
            assertTrue(entryLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<TransitionAttempt> startFuture = executor.submit(() -> {
                await(start);
                try {
                    queueService.startService(managerId, organizationId, queueId, entry.id(),
                            new StartServiceRequest("Desk", "state-race-start"));
                    return new TransitionAttempt("STARTED", null);
                } catch (BusinessException exception) {
                    if (Set.of("NOT_CALLED", "ALREADY_TERMINAL").contains(exception.getCode())) {
                        return new TransitionAttempt(null, exception.getCode());
                    }
                    throw exception;
                }
            });
            Future<TransitionAttempt> noShowFuture = executor.submit(() -> {
                await(start);
                try {
                    queueService.markNoShow(managerId, organizationId, queueId, entry.id());
                    return new TransitionAttempt("NO_SHOW", null);
                } catch (BusinessException exception) {
                    if (Set.of("NOT_CALLED", "ALREADY_IN_SERVICE", "NOT_CALLED_OR_IN_SERVICE")
                            .contains(exception.getCode())) {
                        return new TransitionAttempt(null, exception.getCode());
                    }
                    throw exception;
                }
            });
            start.countDown();
            awaitDatabaseLockWaiters(2);
            releaseEntryLock.countDown();

            TransitionAttempt started = startFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            TransitionAttempt noShow = noShowFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotEquals(started.wasSuccessful(), noShow.wasSuccessful());
            assertNotNull(started.rejectionCode() == null
                    ? noShow.rejectionCode() : started.rejectionCode());

            QueueEntryStatus finalStatus = queueEntryRepository.findById(entry.id())
                    .orElseThrow().getStatus();
            if (finalStatus == QueueEntryStatus.IN_SERVICE) {
                assertEquals("STARTED", started.succeeded());
                assertEquals(QueueAssignmentStatus.ACTIVE,
                        queueAssignmentRepository.findByQueueEntryIdOrderByAssignedAtDesc(entry.id())
                                .get(0).getStatus());
            } else {
                assertEquals(QueueEntryStatus.NO_SHOW, finalStatus);
                assertEquals("NO_SHOW", noShow.succeeded());
                assertEquals(QueueAssignmentStatus.RELEASED,
                        queueAssignmentRepository.findByQueueEntryIdOrderByAssignedAtDesc(entry.id())
                                .get(0).getStatus());
            }
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from queue_events where queue_entry_id = ? "
                            + "and event_type in ('ENTRY_STARTED', 'ENTRY_NO_SHOW')",
                    Integer.class, entry.id()));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from outbox_events where aggregate_id = ? "
                            + "and event_type in ('QUEUE_ENTRY_STARTED', 'QUEUE_ENTRY_NO_SHOW')",
                    Integer.class, entry.id()));
        } finally {
            start.countDown();
            releaseEntryLock.countDown();
            try {
                lockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                stop(executor);
            }
        }
    }

    private Future<?> updateConfiguration(
            ExecutorService executor,
            long organizationId,
            long queueId,
            String description,
            CountDownLatch loaded,
            CountDownLatch update) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            QueueConfiguration configuration = queueConfigurationRepository
                    .findByOrganizationAndId(organizationId, queueId).orElseThrow();
            loaded.countDown();
            await(update);
            configuration.setDescription(description);
            queueConfigurationRepository.saveAndFlush(configuration);
            return null;
        }));
    }

    private Future<?> holdTokenSequenceLock(
            ExecutorService executor,
            long organizationId,
            String datePart,
            CountDownLatch locked,
            CountDownLatch release) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            tokenSequenceRepository.findByOrganizationIdAndQueueCodeAndDatePartForUpdate(
                    organizationId, "TOKEN_RACE", datePart).orElseThrow();
            locked.countDown();
            await(release);
            return null;
        }));
    }

    private Future<?> holdQueueConfigurationLock(
            ExecutorService executor,
            long organizationId,
            long queueId,
            CountDownLatch locked,
            CountDownLatch release) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            queueConfigurationRepository.findByOrganizationAndIdForUpdate(
                    organizationId, queueId).orElseThrow();
            locked.countDown();
            await(release);
            return null;
        }));
    }

    private Future<?> holdQueueEntryLocks(
            ExecutorService executor,
            long organizationId,
            List<Long> entryIds,
            CountDownLatch locked,
            CountDownLatch release) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            for (Long entryId : entryIds) {
                queueEntryRepository.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                        .orElseThrow();
            }
            locked.countDown();
            await(release);
            return null;
        }));
    }

    private void awaitDatabaseLockWaiters(int minimumWaiters) {
        awaitDatabaseLockWaiters(minimumWaiters, TIMEOUT_SECONDS);
    }

    private void awaitDatabaseLockWaiters(int minimumWaiters, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database()
                      and state = 'active'
                      and wait_event_type = 'Lock'
                      and pid <> pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting >= minimumWaiters) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while observing PostgreSQL lock waiters",
                        exception);
            }
        }
        fail("Expected at least " + minimumWaiters + " PostgreSQL transactions waiting on locks");
    }

    private CallAttempt callNextAttempt(
            long actor, long organizationId, long queueId, String idempotencyKey) {
        try {
            CallNextResponse response = queueService.callNext(
                    actor, organizationId, queueId,
                    new CallNextRequest("Desk", idempotencyKey));
            return new CallAttempt(response.entry().id(), null);
        } catch (BusinessException exception) {
            if ("NO_ENTRIES_AVAILABLE".equals(exception.getCode())) {
                return new CallAttempt(null, exception.getCode());
            }
            throw exception;
        }
    }

    private long createOrganization() {
        String name = "Concurrency-" + UUID.randomUUID();
        return jdbc.queryForObject(
                "insert into organizations (name, organization_type, status, created_at, updated_at) "
                        + "values (?, 'TEST', 'ACTIVE', now(), now()) returning id",
                Long.class, name);
    }

    private long createMember(long organizationId, OrganizationRole role) {
        long userId = userIds.getAndIncrement();
        long membershipId = jdbc.queryForObject(
                "insert into memberships (organization_id, auth_user_id, status, created_at, updated_at) "
                        + "values (?, ?, 'ACTIVE', now(), now()) returning id",
                Long.class, organizationId, userId);
        jdbc.update("insert into membership_roles (membership_id, role) values (?, ?)",
                membershipId, role.name());
        return userId;
    }

    private long createQueue(
            long organizationId,
            long managerId,
            String code,
            int capacity,
            OrderingPolicy policy,
            boolean priorityEnabled) {
        QueueConfigurationView created = queueService.createQueueConfig(
                managerId, organizationId,
                new CreateQueueConfigurationRequest(code, null, code, QueueType.GENERIC,
                        policy, capacity, priorityEnabled, null));
        return created.id();
    }

    private long createOpenQueue(
            long organizationId,
            long managerId,
            String code,
            int capacity,
            OrderingPolicy policy,
            boolean priorityEnabled) {
        long queueId = createQueue(
                organizationId, managerId, code, capacity, policy, priorityEnabled);
        queueService.openQueue(managerId, organizationId, queueId);
        return queueId;
    }

    private QueueEntryView register(
            long managerId, long organizationId, long queueId, String externalReference) {
        return register(managerId, organizationId, queueId, externalReference, 0);
    }

    private QueueEntryView register(
            long managerId,
            long organizationId,
            long queueId,
            String externalReference,
            int priority) {
        return queueService.registerParticipant(managerId, organizationId, queueId,
                new CreateQueueEntryRequest(externalReference, null,
                        "Participant " + externalReference, null, priority));
    }

    private CreateQueueEntryRequest request(String externalReference) {
        return new CreateQueueEntryRequest(externalReference, null,
                "Participant " + externalReference, null, 0);
    }

    private boolean expectNotFound(Runnable operation) {
        assertThrows(NotFoundException.class, operation::run);
        return true;
    }

    private boolean isOptimisticLockFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof jakarta.persistence.OptimisticLockException) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMessage(Throwable failure, String message) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(message)) {
                return true;
            }
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating concurrent test", exception);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Concurrent test executor did not stop");
        }
    }

    private record RegistrationAttempt(QueueEntryView entry, String rejectionCode) {}

    private record CallAttempt(Long entryId, String rejectionCode) {}

    private record TransitionAttempt(String succeeded, String rejectionCode) {
        private boolean wasSuccessful() {
            return succeeded != null;
        }
    }
}
