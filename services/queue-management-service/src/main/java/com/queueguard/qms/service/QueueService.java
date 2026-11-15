package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.dto.QueueDtos.*;
import com.queueguard.qms.repo.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main service for queue management operations.
 * Handles all business queue operations with transactional consistency.
 */
@Service
public class QueueService {
    
    private final QueueConfigurationRepository queueConfigRepo;
    private final QueueEntryRepository queueEntryRepo;
    private final QueueAssignmentRepository queueAssignmentRepo;
    private final QueueEventRepository queueEventRepo;
    private final OutboxEventRepository outboxEventRepo;
    private final TenantService tenantService;
    private final TokenGenerator tokenGenerator;
    
    public QueueService(
            QueueConfigurationRepository queueConfigRepo,
            QueueEntryRepository queueEntryRepo,
            QueueAssignmentRepository queueAssignmentRepo,
            QueueEventRepository queueEventRepo,
            OutboxEventRepository outboxEventRepo,
            TenantService tenantService,
            TokenGenerator tokenGenerator) {
        this.queueConfigRepo = queueConfigRepo;
        this.queueEntryRepo = queueEntryRepo;
        this.queueAssignmentRepo = queueAssignmentRepo;
        this.queueEventRepo = queueEventRepo;
        this.outboxEventRepo = outboxEventRepo;
        this.tenantService = tenantService;
        this.tokenGenerator = tokenGenerator;
    }
    
    // ==================== Queue Configuration Operations ====================
    
    @Transactional
    public QueueConfigurationView createQueueConfig(
            Long actor, Long organizationId, CreateQueueConfigurationRequest request) {
        
        // Validate organization membership
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        
        // Check if queue code already exists for this organization
        if (queueConfigRepo.existsByOrganizationIdAndQueueCode(organizationId, request.queueCode())) {
            throw new BusinessException(409, "QUEUE_CODE_EXISTS", 
                    "Queue code already exists for this organization");
        }
        
        // Create queue configuration
        QueueConfiguration config = new QueueConfiguration();
        config.setOrganizationId(organizationId);
        config.setName(request.name().trim());
        config.setDescription(request.description() != null ? request.description().trim() : null);
        config.setQueueCode(request.queueCode().trim().toUpperCase());
        config.setQueueType(request.queueType());
        config.setOrderingPolicy(request.orderingPolicy());
        config.setStatus(QueueStatus.DRAFT);
        config.setMaximumCapacity(request.maximumCapacity());
        config.setPriorityEnabled(request.priorityEnabled());
        config.setEstimatedServiceDurationSeconds(request.estimatedServiceDurationSeconds());
        config.setCreatedBy(actor);
        
        config = queueConfigRepo.save(config);
        
        // Record event
        recordQueueEvent(organizationId, config.getId(), null, 
                QueueEventType.QUEUE_CREATED, actor, null);
        
        return QueueConfigurationView.from(config);
    }
    
    @Transactional(readOnly = true)
    public List<QueueConfigurationView> listQueueConfigs(Long actor, Long organizationId) {
        tenantService.active(actor, organizationId);
        
        return queueConfigRepo.findByOrganizationIdOrderByNameAsc(organizationId)
                .stream()
                .map(QueueConfigurationView::from)
                .toList();
    }
    
    @Transactional(readOnly = true)
    public QueueConfigurationView getQueueConfig(Long actor, Long organizationId, Long queueId) {
        tenantService.active(actor, organizationId);
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        return QueueConfigurationView.from(config);
    }
    
    @Transactional
    public QueueConfigurationView updateQueueConfig(
            Long actor, Long organizationId, Long queueId, UpdateQueueConfigurationRequest request) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Update fields if provided
        if (request.name() != null) {
            config.setName(request.name().trim());
        }
        if (request.description() != null) {
            config.setDescription(request.description().trim());
        }
        if (request.maximumCapacity() != null) {
            config.setMaximumCapacity(request.maximumCapacity());
        }
        if (request.priorityEnabled() != null) {
            config.setPriorityEnabled(request.priorityEnabled());
        }
        if (request.estimatedServiceDurationSeconds() != null) {
            config.setEstimatedServiceDurationSeconds(request.estimatedServiceDurationSeconds());
        }
        
        config = queueConfigRepo.save(config);
        
        // Record event
        recordQueueEvent(organizationId, config.getId(), null, 
                QueueEventType.QUEUE_UPDATED, actor, null);
        
        return QueueConfigurationView.from(config);
    }
    
    @Transactional
    public QueueConfigurationView openQueue(Long actor, Long organizationId, Long queueId) {
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (config.getStatus() == QueueStatus.OPEN) {
            throw new BusinessException(409, "QUEUE_ALREADY_OPEN", "Queue is already open");
        }
        
        if (config.getStatus() == QueueStatus.ARCHIVED) {
            throw new BusinessException(409, "QUEUE_ARCHIVED", "Cannot open an archived queue");
        }
        
        config.setStatus(QueueStatus.OPEN);
        config = queueConfigRepo.save(config);
        
        // Record event
        recordQueueEvent(organizationId, config.getId(), null, 
                QueueEventType.QUEUE_OPENED, actor, null);
        
        return QueueConfigurationView.from(config);
    }
    
    @Transactional
    public QueueConfigurationView pauseQueue(Long actor, Long organizationId, Long queueId) {
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (config.getStatus() != QueueStatus.OPEN) {
            throw new BusinessException(409, "QUEUE_NOT_OPEN", "Queue must be open to pause");
        }
        
        config.setStatus(QueueStatus.PAUSED);
        config = queueConfigRepo.save(config);
        
        // Record event
        recordQueueEvent(organizationId, config.getId(), null, 
                QueueEventType.QUEUE_PAUSED, actor, null);
        
        return QueueConfigurationView.from(config);
    }
    
    @Transactional
    public QueueConfigurationView resumeQueue(Long actor, Long organizationId, Long queueId) {
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (config.getStatus() != QueueStatus.PAUSED) {
            throw new BusinessException(409, "QUEUE_NOT_PAUSED", "Queue must be paused to resume");
        }
        
        config.setStatus(QueueStatus.OPEN);
        config = queueConfigRepo.save(config);
        
        // Record event
        recordQueueEvent(organizationId, config.getId(), null, 
                QueueEventType.QUEUE_RESUMED, actor, null);
        
        return QueueConfigurationView.from(config);
    }
    
    @Transactional
    public QueueConfigurationView closeQueue(Long actor, Long organizationId, Long queueId) {
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (config.getStatus() == QueueStatus.CLOSED || config.getStatus() == QueueStatus.ARCHIVED) {
            throw new BusinessException(409, "QUEUE_ALREADY_CLOSED", "Queue is already closed or archived");
        }
        
        config.setStatus(QueueStatus.CLOSED);
        config = queueConfigRepo.save(config);
        
        // Record event
        recordQueueEvent(organizationId, config.getId(), null, 
                QueueEventType.QUEUE_CLOSED, actor, null);
        
        return QueueConfigurationView.from(config);
    }
    
    // ==================== Queue Entry Operations ====================
    
    @Transactional
    public QueueEntryView createQueueEntry(
            Long actor, Long organizationId, Long queueId, CreateQueueEntryRequest request) {
        
        // Validate organization membership (tenant isolation)
        tenantService.active(actor, organizationId);
        
        // Lock the queue configuration to prevent concurrent capacity checks
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndIdForUpdate(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Check if queue can accept registrations
        if (!config.canAcceptRegistrations()) {
            throw new BusinessException(409, "QUEUE_NOT_ACCEPTING", 
                    "Queue is not accepting new registrations (closed, paused, or archived)");
        }
        
        // Check capacity with atomic count (within the same transaction as the lock)
        if (config.getMaximumCapacity() != null) {
            long currentCount = queueEntryRepo.countByQueueConfigurationIdAndStatusIn(
                    queueId, List.of(QueueEntryStatus.WAITING, QueueEntryStatus.CALLED, QueueEntryStatus.IN_SERVICE));
            if (currentCount >= config.getMaximumCapacity()) {
                throw new BusinessException(409, "QUEUE_FULL", "Queue has reached maximum capacity");
            }
        }
        
        // Validate priority if not enabled
        if (!config.isPriorityEnabled() && request.priority() != null && request.priority() != 0) {
            throw new BusinessException(400, "PRIORITY_NOT_ENABLED", "Priority is not enabled for this queue");
        }
        
        // Generate unique token
        String token = tokenGenerator.generateToken(organizationId, config.getQueueCode());
        
        // Create queue entry
        QueueEntry entry = new QueueEntry();
        entry.setOrganizationId(organizationId);
        entry.setQueueConfigurationId(queueId);
        entry.setTokenNumber(token);
        entry.setExternalReference(request.externalReference());
        entry.setParticipantUserId(request.participantUserId());
        entry.setParticipantName(request.participantName());
        entry.setParticipantEmail(request.participantEmail());
        entry.setPriority(request.priority() != null ? request.priority() : 0);
        entry.setStatus(QueueEntryStatus.WAITING);
        entry.setJoinedAt(Instant.now());
        
        // Calculate estimated wait time if service duration is configured
        if (config.getEstimatedServiceDurationSeconds() != null) {
            long waitingCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(
                    queueId, QueueEntryStatus.WAITING);
            entry.setEstimatedWaitSeconds(
                    (int) (waitingCount * config.getEstimatedServiceDurationSeconds()));
        }
        
        entry = queueEntryRepo.save(entry);
        
        // Record event
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_REGISTERED, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_REGISTERED", entry);
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional
    public QueueEntryView registerParticipant(
            Long actor, Long organizationId, Long queueId, CreateQueueEntryRequest request) {
        
        // Validate organization membership (tenant isolation)
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check authorization for registration
        Long participantUserId = request.participantUserId();
        
        if (participantUserId != null) {
            // Self-registration: participantUserId must equal actor
            if (!participantUserId.equals(actor)) {
                // Registering someone else - need ORG_ADMIN or QUEUE_MANAGER
                if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
                    !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
                    throw new ForbiddenException("Cannot register other participants without ORG_ADMIN or QUEUE_MANAGER role");
                }
            }
            // MEMBER can register themselves - no additional check needed
        } else {
            // External participant (participantUserId is null)
            // Only ORG_ADMIN and QUEUE_MANAGER can register external participants
            if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
                !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
                throw new ForbiddenException("Cannot register external participants without ORG_ADMIN or QUEUE_MANAGER role");
            }
        }
        
        return createQueueEntry(actor, organizationId, queueId, request);
    }
    
    @Transactional(readOnly = true)
    public List<QueueEntryListView> listQueueEntries(
            Long actor, Long organizationId, Long queueId, QueueEntryStatus status, Pageable pageable) {
        
        tenantService.active(actor, organizationId);
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (status != null) {
            return queueEntryRepo.findByQueueConfigurationIdAndStatusOrderByJoinedAtAsc(
                            queueId, status, pageable)
                    .getContent()
                    .stream()
                    .map(QueueEntryListView::from)
                    .toList();
        } else {
            return queueEntryRepo.findByQueueConfigurationIdOrderByJoinedAtAsc(queueId, pageable)
                    .getContent()
                    .stream()
                    .map(QueueEntryListView::from)
                    .toList();
        }
    }
    
    @Transactional(readOnly = true)
    public QueueEntryView getQueueEntry(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        tenantService.active(actor, organizationId);
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional(readOnly = true)
    public QueueEntryPositionView getQueueEntryPosition(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        tenantService.active(actor, organizationId);
        
        QueueEntry entry = queueEntryRepo.findById(entryId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the organization and queue
        if (!entry.getOrganizationId().equals(organizationId) || 
            !entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found");
        }
        
        QueueConfiguration config = queueConfigRepo.findById(queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Calculate position based on ordering policy
        int entriesAhead;
        if (config.getOrderingPolicy() == OrderingPolicy.FIFO) {
            entriesAhead = (int) queueEntryRepo.countEntriesAheadFifo(
                    organizationId, queueId, entry.getJoinedAt(), entry.getId());
        } else {
            entriesAhead = (int) queueEntryRepo.countEntriesAheadPriority(
                    organizationId, queueId, entry.getPriority(), entry.getJoinedAt(), entry.getId());
        }
        
        // Get counts
        long waitingCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.WAITING);
        
        return new QueueEntryPositionView(
                entry.getId(),
                entry.getTokenNumber(),
                entry.getStatus(),
                entriesAhead + 1,  // Position is 1-based
                entriesAhead,
                (int) waitingCount,
                entry.getEstimatedWaitSeconds()
        );
    }
    
    // ==================== Call Next Operations ====================
    
    @Transactional
    public CallNextResponse callNext(
            Long actor, Long organizationId, Long queueId, CallNextRequest request) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions - need to be ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER) &&
            !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER role required");
        }
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndIdForUpdate(
                        organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Find next eligible entry based on ordering policy
        QueueEntry entry;
        if (config.getOrderingPolicy() == OrderingPolicy.FIFO) {
            entry = queueEntryRepo.findNextFifoEntryForUpdate(organizationId, queueId)
                    .orElse(null);
        } else {
            entry = queueEntryRepo.findNextPriorityEntryForUpdate(organizationId, queueId)
                    .orElse(null);
        }
        
        if (entry == null) {
            throw new BusinessException(404, "NO_ENTRIES_AVAILABLE", "No entries available to call");
        }
        
        // Validate can call
        QueueStateMachine.validateCanCall(entry, config);
        
        // Transition to CALLED
        entry.setStatus(QueueEntryStatus.CALLED);
        entry.setCalledAt(Instant.now());
        entry = queueEntryRepo.save(entry);
        
        // Create assignment
        QueueAssignment assignment = new QueueAssignment();
        assignment.setOrganizationId(organizationId);
        assignment.setQueueEntryId(entry.getId());
        assignment.setOperatorUserId(actor);
        assignment.setServicePoint(request.servicePoint());
        assignment.setStatus(QueueAssignmentStatus.ASSIGNED);
        assignment = queueAssignmentRepo.save(assignment);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_CALLED, actor, null);
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ASSIGNMENT_CREATED, actor, null);
        
        // Create outbox events
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_CALLED", entry);
        createOutboxEvent(organizationId, "QueueAssignment", assignment.getId(), 
                "QUEUE_ASSIGNMENT_CREATED", assignment);
        
        // Calculate position
        int position = getPositionInQueue(organizationId, queueId, entry);
        
        return new CallNextResponse(
                QueueEntryView.from(entry),
                QueueAssignmentView.from(assignment),
                position,
                config.getStatus().name()
        );
    }
    
    @Transactional
    public QueueEntryView startService(
            Long actor, Long organizationId, Long queueId, Long entryId, StartServiceRequest request) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER) &&
            !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER role required");
        }
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        QueueConfiguration config = queueConfigRepo.findById(entry.getQueueConfigurationId())
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        // Validate can start
        QueueStateMachine.validateCanStart(entry);
        
        // Update assignment to ACTIVE
        QueueAssignment assignment = queueAssignmentRepo.findByQueueEntryIdAndStatusIn(
                        entryId, List.of(QueueAssignmentStatus.ASSIGNED, QueueAssignmentStatus.ACTIVE))
                .orElseThrow(() -> new NotFoundException("No active assignment found for this entry"));
        
        assignment.setStatus(QueueAssignmentStatus.ACTIVE);
        queueAssignmentRepo.save(assignment);
        
        // Transition to IN_SERVICE
        entry.setStatus(QueueEntryStatus.IN_SERVICE);
        entry.setServiceStartedAt(Instant.now());
        entry = queueEntryRepo.save(entry);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_STARTED, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_STARTED", entry);
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional
    public QueueEntryView completeService(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER) &&
            !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER role required");
        }
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        // Validate can complete
        QueueStateMachine.validateCanComplete(entry);
        
        // Update assignment to COMPLETED
        queueAssignmentRepo.findByQueueEntryIdAndStatusIn(
                        entryId, List.of(QueueAssignmentStatus.ASSIGNED, QueueAssignmentStatus.ACTIVE))
                .ifPresent(a -> {
                    a.setStatus(QueueAssignmentStatus.COMPLETED);
                    a.setReleasedAt(Instant.now());
                    queueAssignmentRepo.save(a);
                });
        
        // Transition to COMPLETED
        entry.setStatus(QueueEntryStatus.COMPLETED);
        entry.setCompletedAt(Instant.now());
        entry = queueEntryRepo.save(entry);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_COMPLETED, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_COMPLETED", entry);
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional
    public QueueEntryView markNoShow(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER) &&
            !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER role required");
        }
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        // Validate can mark as no-show
        QueueStateMachine.validateCanMarkNoShow(entry);
        
        // Release assignment
        queueAssignmentRepo.findByQueueEntryIdAndStatusIn(
                        entryId, List.of(QueueAssignmentStatus.ASSIGNED, QueueAssignmentStatus.ACTIVE))
                .ifPresent(a -> {
                    a.setStatus(QueueAssignmentStatus.RELEASED);
                    a.setReleasedAt(Instant.now());
                    queueAssignmentRepo.save(a);
                });
        
        // Transition to NO_SHOW
        entry.setStatus(QueueEntryStatus.NO_SHOW);
        entry = queueEntryRepo.save(entry);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_NO_SHOW, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_NO_SHOW", entry);
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional
    public QueueEntryView cancelEntry(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        // Check if actor can cancel this entry
        Membership membership = tenantService.active(actor, organizationId);
        
        // If actor is the participant, they can cancel their own entry
        boolean isParticipant = entry.getParticipantUserId() != null && 
                                entry.getParticipantUserId().equals(actor);
        
        // Otherwise, need ORG_ADMIN or QUEUE_MANAGER
        boolean isAdmin = membership.getRoles().contains(OrganizationRole.ORG_ADMIN) ||
                          membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
        
        if (!isParticipant && !isAdmin) {
            throw new ForbiddenException("Cannot cancel this entry");
        }
        
        // Validate can cancel
        QueueStateMachine.validateCanCancel(entry);
        
        // Release assignment if exists
        queueAssignmentRepo.findByQueueEntryIdAndStatusIn(
                        entryId, List.of(QueueAssignmentStatus.ASSIGNED, QueueAssignmentStatus.ACTIVE))
                .ifPresent(a -> {
                    a.setStatus(QueueAssignmentStatus.RELEASED);
                    a.setReleasedAt(Instant.now());
                    queueAssignmentRepo.save(a);
                });
        
        // Transition to CANCELLED
        entry.setStatus(QueueEntryStatus.CANCELLED);
        entry.setCancelledAt(Instant.now());
        entry = queueEntryRepo.save(entry);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_CANCELLED, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_CANCELLED", entry);
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional
    public QueueEntryView requeueEntry(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER) &&
            !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER role required");
        }
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        // Validate can requeue
        QueueStateMachine.validateCanRequeue(entry);
        
        // Lock the queue configuration for capacity check
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndIdForUpdate(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (!config.canAcceptRegistrations()) {
            throw new BusinessException(409, "QUEUE_NOT_ACCEPTING", 
                    "Queue is not accepting new registrations");
        }
        
        // Check capacity before requeueing (entry is already counted, so we check if we're at limit)
        if (config.getMaximumCapacity() != null) {
            long currentCount = queueEntryRepo.countByQueueConfigurationIdAndStatusIn(
                    queueId, List.of(QueueEntryStatus.WAITING, QueueEntryStatus.CALLED, QueueEntryStatus.IN_SERVICE));
            // Since we're moving an entry back to WAITING, check if we'd exceed capacity
            if (currentCount >= config.getMaximumCapacity()) {
                throw new BusinessException(409, "QUEUE_FULL", "Queue has reached maximum capacity");
            }
        }
        
        // Release assignment
        queueAssignmentRepo.findByQueueEntryIdAndStatusIn(
                        entryId, List.of(QueueAssignmentStatus.ASSIGNED, QueueAssignmentStatus.ACTIVE))
                .ifPresent(a -> {
                    a.setStatus(QueueAssignmentStatus.RELEASED);
                    a.setReleasedAt(Instant.now());
                    queueAssignmentRepo.save(a);
                });
        
        // Transition back to WAITING
        entry.setStatus(QueueEntryStatus.WAITING);
        entry.setCalledAt(null);
        entry.setServiceStartedAt(null);
        entry.setJoinedAt(Instant.now());  // Reset joined time
        entry = queueEntryRepo.save(entry);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_REQUEUED, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_REQUEUED", entry);
        
        return QueueEntryView.from(entry);
    }
    
    @Transactional
    public QueueEntryView skipEntry(
            Long actor, Long organizationId, Long queueId, Long entryId) {
        
        Membership membership = tenantService.active(actor, organizationId);
        
        // Check permissions
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN) &&
            !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER) &&
            !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("ORG_ADMIN, QUEUE_MANAGER, or INTERVIEWER role required");
        }
        
        QueueEntry entry = queueEntryRepo.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        
        // Verify entry belongs to the queue
        if (!entry.getQueueConfigurationId().equals(queueId)) {
            throw new NotFoundException("Queue entry not found in this queue");
        }
        
        // Validate can skip
        QueueStateMachine.validateCanSkip(entry);
        
        // Release assignment
        queueAssignmentRepo.findByQueueEntryIdAndStatusIn(
                        entryId, List.of(QueueAssignmentStatus.ASSIGNED, QueueAssignmentStatus.ACTIVE))
                .ifPresent(a -> {
                    a.setStatus(QueueAssignmentStatus.RELEASED);
                    a.setReleasedAt(Instant.now());
                    queueAssignmentRepo.save(a);
                });
        
        // Transition to SKIPPED
        entry.setStatus(QueueEntryStatus.SKIPPED);
        entry = queueEntryRepo.save(entry);
        
        // Record events
        recordQueueEvent(organizationId, queueId, entry.getId(), 
                QueueEventType.ENTRY_SKIPPED, actor, null);
        
        // Create outbox event
        createOutboxEvent(organizationId, "QueueEntry", entry.getId(), 
                "QUEUE_ENTRY_SKIPPED", entry);
        
        return QueueEntryView.from(entry);
    }
    
    // ==================== Statistics Operations ====================
    
    @Transactional(readOnly = true)
    public QueueStatisticsView getQueueStatistics(
            Long actor, Long organizationId, Long queueId) {
        
        tenantService.active(actor, organizationId);
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Count entries by status
        long totalEntries = queueEntryRepo.countByQueueConfigurationId(queueId);
        long waitingCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.WAITING);
        long calledCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.CALLED);
        long inServiceCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.IN_SERVICE);
        long completedCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.COMPLETED);
        long cancelledCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.CANCELLED);
        long noShowCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.NO_SHOW);
        long skippedCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.SKIPPED);
        
        // Calculate current capacity usage
        long activeCount = waitingCount + calledCount + inServiceCount;
        int capacityUsage = config.getMaximumCapacity() != null ? 
                (int) ((activeCount * 100) / config.getMaximumCapacity()) : 0;
        
        // Calculate average service duration (simple average)
        double avgServiceDuration = 0;
        if (completedCount > 0) {
            // In a real implementation, we would query for actual service durations
            // For now, use the configured estimate
            if (config.getEstimatedServiceDurationSeconds() != null) {
                avgServiceDuration = config.getEstimatedServiceDurationSeconds();
            }
        }
        
        return new QueueStatisticsView(
                config.getId(),
                config.getName(),
                config.getQueueCode(),
                config.getStatus(),
                (int) totalEntries,
                (int) waitingCount,
                (int) calledCount,
                (int) inServiceCount,
                (int) completedCount,
                (int) cancelledCount,
                (int) noShowCount,
                (int) skippedCount,
                avgServiceDuration,
                capacityUsage,
                config.getMaximumCapacity()
        );
    }
    
    @Transactional(readOnly = true)
    public QueueCurrentStateView getQueueCurrentState(
            Long actor, Long organizationId, Long queueId) {
        
        tenantService.active(actor, organizationId);
        
        QueueConfiguration config = queueConfigRepo.findByOrganizationAndId(organizationId, queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        // Find currently serving entry (IN_SERVICE)
        QueueEntry serving = queueEntryRepo.findByQueueConfigurationIdAndStatusOrderByJoinedAtAsc(
                        queueId, QueueEntryStatus.IN_SERVICE)
                .stream()
                .findFirst()
                .orElse(null);
        
        // Find next in queue (first WAITING)
        QueueEntry next = queueEntryRepo.findByQueueConfigurationIdAndStatusOrderByJoinedAtAsc(
                        queueId, QueueEntryStatus.WAITING)
                .stream()
                .findFirst()
                .orElse(null);
        
        // Count waiting
        long waitingCount = queueEntryRepo.countByQueueConfigurationIdAndStatus(queueId, QueueEntryStatus.WAITING);
        
        return new QueueCurrentStateView(
                config.getId(),
                config.getName(),
                config.getStatus(),
                serving != null ? QueueEntryListView.from(serving) : null,
                next != null ? QueueEntryListView.from(next) : null,
                (int) waitingCount
        );
    }
    
    // ==================== Helper Methods ====================
    
    private int getPositionInQueue(Long organizationId, Long queueId, QueueEntry entry) {
        QueueConfiguration config = queueConfigRepo.findById(queueId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        
        if (config.getOrderingPolicy() == OrderingPolicy.FIFO) {
            long count = queueEntryRepo.countEntriesAheadFifo(
                    organizationId, queueId, entry.getJoinedAt(), entry.getId());
            return (int) count + 1;
        } else {
            long count = queueEntryRepo.countEntriesAheadPriority(
                    organizationId, queueId, entry.getPriority(), entry.getJoinedAt(), entry.getId());
            return (int) count + 1;
        }
    }
    
    private void recordQueueEvent(Long organizationId, Long queueConfigId, 
                                   Long queueEntryId, QueueEventType eventType, 
                                   Long actorUserId, String eventPayload) {
        QueueEvent event = new QueueEvent();
        event.setOrganizationId(organizationId);
        event.setQueueConfigurationId(queueConfigId);
        event.setQueueEntryId(queueEntryId);
        event.setEventType(eventType);
        event.setActorUserId(actorUserId);
        event.setEventPayload(eventPayload);
        event.setOccurredAt(Instant.now());
        queueEventRepo.save(event);
    }
    
    private void createOutboxEvent(Long organizationId, String aggregateType, 
                                    Long aggregateId, String eventType, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setOrganizationId(organizationId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(serializePayload(payload));
        event.setProcessingStatus(OutboxEventStatus.PENDING);
        event.setIdempotencyKey(java.util.UUID.randomUUID().toString());
        outboxEventRepo.save(event);
    }
    
    private String serializePayload(Object payload) {
        // Simple serialization - in production, use JSON
        if (payload == null) {
            return "{}";
        }
        
        if (payload instanceof QueueEntry qe) {
            return String.format(
                    "{\"entryId\":%d,\"token\":\"%s\",\"status\":\"%s\"}",
                    qe.getId(), qe.getTokenNumber(), qe.getStatus()
            );
        } else if (payload instanceof QueueAssignment qa) {
            return String.format(
                    "{\"assignmentId\":%d,\"entryId\":%d,\"operatorId\":%d}",
                    qa.getId(), qa.getQueueEntryId(), qa.getOperatorUserId()
            );
        }
        
        return payload.toString();
    }
}
