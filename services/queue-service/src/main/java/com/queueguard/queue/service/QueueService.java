package com.queueguard.queue.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.queue.dto.QueueDtos.*;
import com.queueguard.queue.entity.Message;
import com.queueguard.queue.entity.MessageStatus;
import com.queueguard.queue.entity.QueueDef;
import com.queueguard.queue.queue.RedisReadyQueue;
import com.queueguard.queue.repo.MessageRepository;
import com.queueguard.queue.repo.QueueDefRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final QueueDefRepository queues;
    private final MessageRepository messages;
    private final RedisReadyQueue readyQueue;
    private final long visibilityTimeoutMs;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingQueueWork = new AtomicLong();
    private final AtomicLong claimedQueueWork = new AtomicLong();
    private final AtomicLong queueStateSnapshotAtMillis =
            new AtomicLong(Long.MIN_VALUE);
    private final Clock clock;

    @Autowired
    public QueueService(QueueDefRepository queues,
                        MessageRepository messages,
                        RedisReadyQueue readyQueue,
                        @Value("${queueguard.visibility-timeout-ms:60000}") long visibilityTimeoutMs,
                        MeterRegistry meterRegistry,
                        Clock clock) {
        this.queues = queues;
        this.messages = messages;
        this.readyQueue = readyQueue;
        this.visibilityTimeoutMs = visibilityTimeoutMs;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        Gauge.builder("queueguard.queue.pending", pendingQueueWork, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("queueguard.queue.claimed", claimedQueueWork, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("queueguard.queue.state.snapshot.age.seconds", queueStateSnapshotAtMillis,
                        timestamp -> {
                            long loadedAt = timestamp.get();
                            return loadedAt == Long.MIN_VALUE
                                    ? Double.NaN
                                    : Math.max(0, (clock.millis() - loadedAt) / 1000.0);
                        })
                .register(meterRegistry);
    }

    public QueueService(QueueDefRepository queues,
                        MessageRepository messages,
                        RedisReadyQueue readyQueue,
                        long visibilityTimeoutMs,
                        MeterRegistry meterRegistry) {
        this(queues, messages, readyQueue, visibilityTimeoutMs, meterRegistry,
                Clock.systemUTC());
    }

    // ------------------------------------------------------------------ queues

    @Transactional
    public QueueView createQueue(CreateQueueRequest req) {
        return measure("create_queue", () -> createQueueDefinition(req));
    }

    private QueueView createQueueDefinition(CreateQueueRequest req) {
        if (queues.existsByName(req.name())) {
            throw new BusinessException(409, "QUEUE_EXISTS", "Queue already exists: " + req.name());
        }
        QueueDef def = new QueueDef();
        def.setName(req.name());
        def.setDescription(req.description());
        if (req.maxConcurrency() != null) def.setMaxConcurrency(req.maxConcurrency());
        if (req.maxAttempts() != null) def.setMaxAttempts(req.maxAttempts());
        queues.save(def);
        return toQueueView(def);
    }

    @Transactional(readOnly = true)
    public List<QueueView> listQueues() {
        List<QueueView> views = queues.findAll().stream().map(this::toQueueView).toList();
        long pending = views.stream().mapToLong(QueueView::pending).sum();
        long claimed = views.stream().mapToLong(QueueView::claimed).sum();
        pendingQueueWork.set(pending);
        claimedQueueWork.set(claimed);
        queueStateSnapshotAtMillis.set(clock.millis());
        return views;
    }

    @Transactional(readOnly = true)
    public StatsResponse stats(String queueName) {
        QueueDef def = requireQueue(queueName);
        Map<MessageStatus, Long> counts = statusCounts(queueName);
        return new StatsResponse(queueName,
                counts.getOrDefault(MessageStatus.PENDING, 0L),
                counts.getOrDefault(MessageStatus.CLAIMED, 0L),
                counts.getOrDefault(MessageStatus.DONE, 0L),
                counts.getOrDefault(MessageStatus.DEAD, 0L),
                readyQueue.depth(queueName),
                def.getMaxConcurrency(), def.getMaxAttempts());
    }

    // ------------------------------------------------------------------ messages

    @Transactional
    public EnqueueResponse enqueue(String queueName, EnqueueRequest req) {
        return measure("enqueue", () -> enqueueMessage(queueName, req));
    }

    private EnqueueResponse enqueueMessage(String queueName, EnqueueRequest req) {
        QueueDef def = requireQueue(queueName);

        String idemKey = req.idempotencyKey();
        if (idemKey != null && !idemKey.isBlank()) {
            Optional<Message> existing = messages.findByQueueNameAndIdempotencyKey(queueName, idemKey);
            if (existing.isPresent()) {
                count("queueguard.queue.messages", "result", "duplicate_enqueue");
                return new EnqueueResponse(existing.get().getId(), true, queueName, existing.get().getStatus().name());
            }
        }

        Message msg = new Message();
        msg.setQueueName(queueName);
        msg.setPayload(req.payload());
        msg.setPriority(req.priority() == null ? 5 : req.priority());
        msg.setStatus(MessageStatus.PENDING);
        if (idemKey != null && !idemKey.isBlank()) {
            msg.setIdempotencyKey(idemKey);
        }
        long delayMs = req.delayMs() == null ? 0 : Math.max(0, req.delayMs());
        Instant readyAt = Instant.now().plusMillis(delayMs);
        msg.setNextAttemptAt(readyAt);
        messages.save(msg);

        readyQueue.push(queueName, msg.getId(), msg.getPriority(), readyAt);
        log.info("Enqueued message {} on {} (priority={}, delayMs={})", msg.getId(), queueName, msg.getPriority(), delayMs);
        count("queueguard.queue.messages", "result", "enqueued");
        return new EnqueueResponse(msg.getId(), false, queueName, "PENDING");
    }

    /**
     * Claim a batch of ready messages for a worker.
     * Concurrency control: never hands out more than (maxConcurrency - in-flight) messages.
     */
    @Transactional
    public ClaimResponse claim(String queueName, ClaimRequest req) {
        return measure("claim", () -> claimMessages(queueName, req));
    }

    private ClaimResponse claimMessages(String queueName, ClaimRequest req) {
        QueueDef def = requireQueue(queueName);
        String workerId = (req.workerId() == null || req.workerId().isBlank()) ? "anonymous-worker" : req.workerId();
        int want = req.max() == null ? 1 : req.max();

        long inflight = messages.countByQueueNameAndStatus(queueName, MessageStatus.CLAIMED);
        int allowance = (int) Math.min(want, Math.max(0, def.getMaxConcurrency() - inflight));
        if (allowance == 0) {
            return new ClaimResponse(queueName, workerId, 0, List.of());
        }

        List<String> popped = readyQueue.pop(queueName, allowance);
        Instant now = Instant.now();
        List<MessageView> claimed = new ArrayList<>();

        for (String id : popped) {
            int updated = messages.claimOne(id, workerId, now, MessageStatus.PENDING, MessageStatus.CLAIMED);
            if (updated == 1) {
                messages.findById(id).ifPresent(m -> claimed.add(toMessageView(m)));
            } else {
                // Row was not claimable; re-queue it at its proper time so nothing is lost.
                messages.findById(id).ifPresent(m -> {
                    if (m.getStatus() == MessageStatus.PENDING) {
                        readyQueue.pushIfAbsent(queueName, m.getId(), m.getPriority(), m.getNextAttemptAt());
                    }
                });
            }
        }
        if (!claimed.isEmpty()) {
            count("queueguard.queue.messages", "result", "claimed", claimed.size());
        }
        return new ClaimResponse(queueName, workerId, claimed.size(), claimed);
    }

    @Transactional
    public MessageView ack(String messageId, WorkerActionRequest req) {
        return measure("ack", () -> acknowledge(messageId, req));
    }

    private MessageView acknowledge(String messageId, WorkerActionRequest req) {
        Message msg = requireMessage(messageId);
        assertOwnership(msg, req.workerId());
        int updated = messages.transition(messageId, MessageStatus.CLAIMED, MessageStatus.DONE,
                0, Instant.now(), null, Instant.now());
        if (updated == 0) {
            throw new BusinessException(409, "NOT_CLAIMED", "Message is not in CLAIMED state");
        }
        count("queueguard.queue.messages", "result", "acknowledged");
        return toMessageView(requireMessage(messageId));
    }

    @Transactional
    public MessageView nack(String messageId, WorkerActionRequest req) {
        return measure("nack", () -> negativeAcknowledge(messageId, req));
    }

    private MessageView negativeAcknowledge(String messageId, WorkerActionRequest req) {
        Message msg = requireMessage(messageId);
        assertOwnership(msg, req.workerId());
        QueueDef def = requireQueue(msg.getQueueName());
        String error = req.error() == null ? "nack by worker" : req.error();

        int attemptsAfter = msg.getAttempts() + 1;
        if (attemptsAfter >= def.getMaxAttempts()) {
            messages.transition(messageId, MessageStatus.CLAIMED, MessageStatus.DEAD,
                    1, Instant.now(), error, Instant.now());
            readyQueue.remove(msg.getQueueName(), messageId);
            log.warn("Message {} dead-lettered after {} attempts: {}", messageId, attemptsAfter, error);
            count("queueguard.queue.messages", "result", "dead_lettered");
        } else {
            long backoffMs = backoffMs(attemptsAfter);
            Instant nextAt = Instant.now().plusMillis(backoffMs);
            messages.transition(messageId, MessageStatus.CLAIMED, MessageStatus.PENDING,
                    1, nextAt, error, Instant.now());
            readyQueue.push(msg.getQueueName(), messageId, msg.getPriority(), nextAt);
            log.info("Message {} nacked (attempt {}/{}), retry in {} ms", messageId, attemptsAfter, def.getMaxAttempts(), backoffMs);
            count("queueguard.queue.messages", "result", "retry_scheduled");
        }
        return toMessageView(requireMessage(messageId));
    }

    // ------------------------------------------------------------------ dead letters

    @Transactional(readOnly = true)
    public List<MessageView> deadLetters(String queueName, int limit) {
        requireQueue(queueName);
        return messages.findByQueueNameAndStatusOrderByCreatedAtDesc(queueName, MessageStatus.DEAD,
                        PageRequest.of(0, Math.min(limit, 200))).stream()
                .map(this::toMessageView).toList();
    }

    @Transactional
    public MessageView requeueDead(String queueName, String messageId) {
        return measure("requeue_dead", () -> requeueDeadMessage(queueName, messageId));
    }

    private MessageView requeueDeadMessage(String queueName, String messageId) {
        Message msg = requireMessage(messageId);
        if (!msg.getQueueName().equals(queueName)) {
            throw new NotFoundException("Message not in queue " + queueName);
        }
        int updated = messages.transition(messageId, MessageStatus.DEAD, MessageStatus.PENDING,
                0, Instant.now(), null, Instant.now());
        if (updated == 0) {
            throw new BusinessException(409, "NOT_DEAD", "Message is not in DEAD state");
        }
        Message reloaded = requireMessage(messageId);
        readyQueue.push(queueName, messageId, reloaded.getPriority(), Instant.now());
        return toMessageView(reloaded);
    }

    // ------------------------------------------------------------------ background healing

    /**
     * Visibility-timeout reaper: workers that crash mid-job leave messages CLAIMED forever.
     * After the timeout we requeue them (counting an attempt), or dead-letter them if exhausted.
     */
    @Scheduled(fixedDelayString = "${queueguard.reaper-interval-ms:15000}")
    public void reapExpiredClaims() {
        Instant cutoff = Instant.now().minusMillis(visibilityTimeoutMs);
        List<Message> expired = messages.findByStatusAndClaimedAtBefore(MessageStatus.CLAIMED, cutoff, PageRequest.of(0, 200));
        for (Message msg : expired) {
            try {
                QueueDef def = requireQueue(msg.getQueueName());
                int attemptsAfter = msg.getAttempts() + 1;
                if (attemptsAfter >= def.getMaxAttempts()) {
                    messages.transition(msg.getId(), MessageStatus.CLAIMED, MessageStatus.DEAD,
                            1, Instant.now(), "visibility timeout (worker crash?)", Instant.now());
                } else {
                    messages.transition(msg.getId(), MessageStatus.CLAIMED, MessageStatus.PENDING,
                            1, Instant.now(), "visibility timeout (worker crash?)", Instant.now());
                    readyQueue.push(msg.getQueueName(), msg.getId(), msg.getPriority(), Instant.now());
                }
                log.warn("Reaper reclaimed message {} from worker {}", msg.getId(), msg.getClaimedBy());
            } catch (Exception e) {
                log.error("Reaper failed on message {}: {}", msg.getId(), e.getMessage());
            }
        }
    }

    /**
     * Reconciler: guarantees every PENDING message exists in the Redis ready-set.
     * Heals drift after Redis restarts or partial failures.
     */
    @Scheduled(fixedDelayString = "${queueguard.reconcile-interval-ms:60000}")
    public void reconcilePending() {
        List<Message> pending = messages.findByStatus(MessageStatus.PENDING, PageRequest.of(0, 500));
        for (Message msg : pending) {
            readyQueue.pushIfAbsent(msg.getQueueName(), msg.getId(), msg.getPriority(), msg.getNextAttemptAt());
        }
        if (!pending.isEmpty()) {
            log.debug("Reconciled {} pending messages into ready sets", pending.size());
        }
    }

    // ------------------------------------------------------------------ helpers

    private QueueDef requireQueue(String name) {
        return queues.findByName(name).orElseThrow(() -> new NotFoundException("Queue not found: " + name));
    }

    private Message requireMessage(String id) {
        return messages.findById(id).orElseThrow(() -> new NotFoundException("Message not found: " + id));
    }

    private void assertOwnership(Message msg, String workerId) {
        if (workerId != null && !workerId.isBlank() && msg.getClaimedBy() != null && !msg.getClaimedBy().equals(workerId)) {
            throw new ForbiddenException("Message claimed by " + msg.getClaimedBy() + ", not " + workerId);
        }
    }

    private Map<MessageStatus, Long> statusCounts(String queueName) {
        Map<MessageStatus, Long> counts = new EnumMap<>(MessageStatus.class);
        for (Object[] row : messages.countGroupedByStatus(queueName)) {
            counts.put((MessageStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static long backoffMs(int attempts) {
        return Math.min(60_000L, 1000L * (1L << Math.min(attempts, 6)));
    }

    private <T> T measure(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = action.get();
            count("queueguard.queue.operations", "operation", operation, "outcome", "success");
            return result;
        } catch (RuntimeException exception) {
            count("queueguard.queue.operations", "operation", operation, "outcome", "failure");
            throw exception;
        } finally {
            sample.stop(Timer.builder("queueguard.queue.operation.duration")
                    .tag("operation", operation)
                    .register(meterRegistry));
        }
    }

    private void count(String name, String key, String value) {
        count(name, key, value, 1.0);
    }

    private void count(String name, String key, String value, double amount) {
        Counter.builder(name)
                .tag(key, value)
                .register(meterRegistry)
                .increment(amount);
    }

    private void count(String name, String key1, String value1, String key2, String value2) {
        Counter.builder(name)
                .tag(key1, value1)
                .tag(key2, value2)
                .register(meterRegistry)
                .increment();
    }

    private QueueView toQueueView(QueueDef def) {
        Map<MessageStatus, Long> counts = statusCounts(def.getName());
        return new QueueView(def.getName(), def.getDescription(), def.getMaxConcurrency(), def.getMaxAttempts(),
                counts.getOrDefault(MessageStatus.PENDING, 0L),
                counts.getOrDefault(MessageStatus.CLAIMED, 0L),
                counts.getOrDefault(MessageStatus.DONE, 0L),
                counts.getOrDefault(MessageStatus.DEAD, 0L),
                readyQueue.depth(def.getName()),
                def.getCreatedAt().toString());
    }

    private MessageView toMessageView(Message m) {
        return new MessageView(m.getId(), m.getQueueName(), m.getPayload(), m.getPriority(), m.getStatus().name(),
                m.getAttempts(), m.getError(), m.getClaimedBy(),
                m.getNextAttemptAt() == null ? null : m.getNextAttemptAt().toString(),
                m.getCreatedAt().toString());
    }
}
