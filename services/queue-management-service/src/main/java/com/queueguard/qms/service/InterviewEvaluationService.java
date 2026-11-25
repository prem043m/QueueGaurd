package com.queueguard.qms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.CandidateRound;
import com.queueguard.qms.domain.InterviewEvaluation;
import com.queueguard.qms.domain.InterviewerAssignment;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.domain.OutboxEvent;
import com.queueguard.qms.domain.OutboxEventStatus;
import com.queueguard.qms.dto.Phase3Dtos.InterviewEvaluationView;
import com.queueguard.qms.dto.Phase3Dtos.SubmitInterviewEvaluationRequest;
import com.queueguard.qms.repo.CandidateRoundRepository;
import com.queueguard.qms.repo.InterviewEvaluationRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewEvaluationService {
    private static final String SUBMITTED_EVENT = "EVALUATION_SUBMITTED";
    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = new BigDecimal("100");

    private final CandidateRoundRepository candidateRoundRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final InterviewerAssignmentRepository assignmentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final Phase3Access access;
    private final ObjectMapper objectMapper;
    private final OutboxTraceContext outboxTraceContext;

    public InterviewEvaluationService(
            CandidateRoundRepository candidateRoundRepository,
            InterviewEvaluationRepository evaluationRepository,
            InterviewerAssignmentRepository assignmentRepository,
            OutboxEventRepository outboxEventRepository,
            Phase3Access access,
            ObjectMapper objectMapper,
            OutboxTraceContext outboxTraceContext) {
        this.candidateRoundRepository = candidateRoundRepository;
        this.evaluationRepository = evaluationRepository;
        this.assignmentRepository = assignmentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.access = access;
        this.objectMapper = objectMapper;
        this.outboxTraceContext = outboxTraceContext;
    }

    @Transactional
    public InterviewEvaluationView submit(
            Long actorId, Long organizationId, Long candidateRoundId,
            SubmitInterviewEvaluationRequest request) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        requireInterviewer(actor);
        validateRequest(request);

        CandidateRound candidateRound = candidateRoundRepository
                .findByIdAndOrganizationId(candidateRoundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate round not found"));
        List<InterviewerAssignment> assignments = assignmentRepository
                .findActiveAssignmentsForUpdate(organizationId,
                        candidateRound.getInterviewRoundId(), actorId);
        if (assignments.isEmpty()) {
            throw new ForbiddenException(
                    "An active interviewer assignment is required to submit an evaluation");
        }

        candidateRound = candidateRoundRepository
                .findByIdAndOrganizationIdForUpdate(candidateRoundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate round not found"));
        if (evaluationRepository.existsByOrganizationIdAndCandidateRoundIdAndInterviewerUserId(
                organizationId, candidateRoundId, actorId)) {
            throw conflict("EVALUATION_ALREADY_SUBMITTED",
                    "An interviewer may submit only one evaluation per candidate round");
        }

        InterviewEvaluation evaluation = new InterviewEvaluation();
        evaluation.setOrganizationId(organizationId);
        evaluation.setCandidateRoundId(candidateRoundId);
        evaluation.setInterviewerUserId(actorId);
        evaluation.setTechnicalScore(request.technicalScore());
        evaluation.setCommunicationScore(request.communicationScore());
        evaluation.setProblemSolvingScore(request.problemSolvingScore());
        evaluation.setOverallScore(request.overallScore());
        evaluation.setRecommendation(request.recommendation());
        evaluation.setFeedback(request.feedback().trim());
        evaluation.setSubmittedAt(Instant.now());
        evaluation = evaluationRepository.saveAndFlush(evaluation);
        saveOutboxEvent(evaluation);
        return InterviewEvaluationView.from(evaluation);
    }

    @Transactional(readOnly = true)
    public List<InterviewEvaluationView> listForCandidateRound(
            Long actorId, Long organizationId, Long candidateRoundId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateRound candidateRound = candidateRoundRepository
                .findByIdAndOrganizationId(candidateRoundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate round not found"));
        if (!isManager(actor)
                && (!actor.getRoles().contains(OrganizationRole.INTERVIEWER)
                    || !assignmentRepository.existsActiveAssignment(organizationId,
                            candidateRound.getInterviewRoundId(), actorId))) {
            throw new ForbiddenException(
                    "Drive manager or assigned interviewer permission required");
        }
        return evaluationRepository
                .findByOrganizationIdAndCandidateRoundIdOrderBySubmittedAtAscIdAsc(
                        organizationId, candidateRoundId)
                .stream().map(InterviewEvaluationView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<InterviewEvaluationView> listMine(Long actorId, Long organizationId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        requireInterviewer(actor);
        return evaluationRepository
                .findByOrganizationIdAndInterviewerUserIdOrderBySubmittedAtDescIdDesc(
                        organizationId, actorId)
                .stream().map(InterviewEvaluationView::from).toList();
    }

    private void saveOutboxEvent(InterviewEvaluation evaluation) {
        OutboxEvent event = new OutboxEvent();
        event.setOrganizationId(evaluation.getOrganizationId());
        event.setAggregateType("InterviewEvaluation");
        event.setAggregateId(evaluation.getId());
        event.setEventType(SUBMITTED_EVENT);
        event.setPayload(serializePayload(evaluation));
        event.setProcessingStatus(OutboxEventStatus.PENDING);
        event.setIdempotencyKey("evaluation-submitted:"
                + evaluation.getOrganizationId() + ":" + evaluation.getId());
        outboxTraceContext.capture(event);
        outboxEventRepository.saveAndFlush(event);
    }

    private String serializePayload(InterviewEvaluation evaluation) {
        try {
            return objectMapper.writeValueAsString(new EvaluationSubmittedPayload(
                    evaluation.getOrganizationId(), evaluation.getCandidateRoundId(),
                    evaluation.getId(), evaluation.getInterviewerUserId()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize interview evaluation outbox payload", exception);
        }
    }

    private static void validateRequest(SubmitInterviewEvaluationRequest request) {
        if (request == null
                || !validScore(request.technicalScore())
                || !validScore(request.communicationScore())
                || !validScore(request.problemSolvingScore())
                || !validScore(request.overallScore())
                || request.recommendation() == null
                || request.feedback() == null
                || request.feedback().isBlank()
                || request.feedback().length() > 4000) {
            throw new BusinessException(400, "INVALID_EVALUATION",
                    "Evaluation fields and scores are invalid");
        }
    }

    private static boolean validScore(BigDecimal score) {
        return score != null && score.compareTo(MIN_SCORE) >= 0
                && score.compareTo(MAX_SCORE) <= 0;
    }

    private static void requireInterviewer(Membership actor) {
        if (!actor.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("INTERVIEWER role required");
        }
    }

    private static boolean isManager(Membership actor) {
        return actor.getRoles().contains(OrganizationRole.ORG_ADMIN)
                || actor.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    private record EvaluationSubmittedPayload(
            Long organizationId, Long candidateRoundId,
            Long evaluationId, Long interviewerUserId) {}
}
