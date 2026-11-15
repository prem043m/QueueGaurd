package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.InterviewRound;
import com.queueguard.qms.domain.InterviewRoundStatus;
import com.queueguard.qms.domain.InterviewerAssignment;
import com.queueguard.qms.domain.InterviewerAssignmentStatus;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.repo.InterviewRoundRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewerAssignmentService {
    private final InterviewerAssignmentRepository assignmentRepository;
    private final InterviewRoundRepository roundRepository;
    private final Phase3Access access;

    public InterviewerAssignmentService(
            InterviewerAssignmentRepository assignmentRepository,
            InterviewRoundRepository roundRepository,
            Phase3Access access) {
        this.assignmentRepository = assignmentRepository;
        this.roundRepository = roundRepository;
        this.access = access;
    }

    @Transactional
    public InterviewerAssignment assign(
            Long actorId, Long organizationId, Long roundId,
            Long interviewerUserId, String panelName, String servicePoint) {
        access.requireDriveManager(actorId, organizationId);
        InterviewRound round = requireAssignableRound(roundId, organizationId);
        access.requireRole(interviewerUserId, organizationId, OrganizationRole.INTERVIEWER);
        String normalizedPanel = trimToNull(panelName);
        String normalizedPoint = trimToNull(servicePoint);

        var existing = assignmentRepository
                .findByOrganizationIdAndInterviewRoundIdAndInterviewerUserIdAndPanelName(
                        organizationId, roundId, interviewerUserId, normalizedPanel);
        if (existing.isPresent()) {
            InterviewerAssignment assignment = existing.get();
            if (assignment.getStatus() != InterviewerAssignmentStatus.RELEASED) {
                throw conflict("DUPLICATE_INTERVIEWER_ASSIGNMENT",
                        "Interviewer is already assigned to this round and panel");
            }
            assignment.setStatus(InterviewerAssignmentStatus.ASSIGNED);
            assignment.setAssignedAt(Instant.now());
            assignment.setServicePoint(normalizedPoint);
            return assignmentRepository.save(assignment);
        }

        InterviewerAssignment assignment = new InterviewerAssignment();
        assignment.setOrganizationId(organizationId);
        assignment.setInterviewRoundId(roundId);
        assignment.setInterviewerUserId(interviewerUserId);
        assignment.setPanelName(normalizedPanel);
        assignment.setServicePoint(normalizedPoint);
        assignment.setAssignedAt(Instant.now());
        assignment.setStatus(InterviewerAssignmentStatus.ASSIGNED);
        return assignmentRepository.save(assignment);
    }

    @Transactional(readOnly = true)
    public List<InterviewerAssignment> listForRound(
            Long actorId, Long organizationId, Long roundId) {
        access.requireDriveManager(actorId, organizationId);
        if (roundRepository.findByIdAndOrganizationId(roundId, organizationId).isEmpty()) {
            throw new NotFoundException("Interview round not found");
        }
        return assignmentRepository
                .findByOrganizationIdAndInterviewRoundIdOrderByAssignedAt(
                        organizationId, roundId);
    }

    @Transactional(readOnly = true)
    public List<InterviewerAssignment> listForInterviewer(
            Long actorId, Long organizationId, Long interviewerUserId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        if (actorId.equals(interviewerUserId)) {
            if (!actor.getRoles().contains(OrganizationRole.INTERVIEWER)) {
                throw new com.queueguard.common.exception.ForbiddenException(
                        "INTERVIEWER role required");
            }
        } else if (!isManager(actor)) {
            throw new com.queueguard.common.exception.ForbiddenException(
                    "Interviewers may view only their own assignments");
        }
        return assignmentRepository
                .findByOrganizationIdAndInterviewerUserIdOrderByAssignedAtDesc(
                        organizationId, interviewerUserId);
    }

    @Transactional
    public InterviewerAssignment changeStatus(
            Long actorId, Long organizationId, Long assignmentId,
            InterviewerAssignmentStatus nextStatus) {
        access.requireDriveManager(actorId, organizationId);
        InterviewerAssignment assignment = assignmentRepository
                .findByIdAndOrganizationId(assignmentId, organizationId)
                .orElseThrow(() -> new NotFoundException("Interviewer assignment not found"));
        requireAssignableRound(assignment.getInterviewRoundId(), organizationId);

        boolean valid = switch (assignment.getStatus()) {
            case ASSIGNED -> nextStatus == InterviewerAssignmentStatus.ACTIVE
                    || nextStatus == InterviewerAssignmentStatus.RELEASED;
            case ACTIVE -> nextStatus == InterviewerAssignmentStatus.RELEASED;
            case RELEASED -> false;
        };
        if (!valid) {
            throw conflict("INVALID_ASSIGNMENT_TRANSITION",
                    "Assignment status transition is not permitted");
        }
        assignment.setStatus(nextStatus);
        return assignmentRepository.save(assignment);
    }

    @Transactional(readOnly = true)
    public InterviewerAssignment get(
            Long actorId, Long organizationId, Long assignmentId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        InterviewerAssignment assignment = assignmentRepository
                .findByIdAndOrganizationId(assignmentId, organizationId)
                .orElseThrow(() -> new NotFoundException("Interviewer assignment not found"));
        if (!isManager(actor)
                && (!actorId.equals(assignment.getInterviewerUserId())
                    || !actor.getRoles().contains(OrganizationRole.INTERVIEWER))) {
            throw new com.queueguard.common.exception.ForbiddenException(
                    "Interviewer assignment belongs to another user");
        }
        return assignment;
    }

    private InterviewRound requireAssignableRound(Long roundId, Long organizationId) {
        InterviewRound round = roundRepository
                .findByIdAndOrganizationIdForUpdate(roundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Interview round not found"));
        if (round.getStatus() == InterviewRoundStatus.CLOSED) {
            throw conflict("ROUND_CLOSED", "Assignments cannot change for a closed round");
        }
        return round;
    }

    private static boolean isManager(Membership membership) {
        return membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                || membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }
}
