package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.CandidateRound;
import com.queueguard.qms.domain.InterviewerAssignment;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.repo.CandidateRoundRepository;
import com.queueguard.qms.repo.InterviewEvaluationRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.OutboxEventRepository;
import com.queueguard.qms.dto.Phase3Dtos.SubmitInterviewEvaluationRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterviewEvaluationServiceTest {
    private CandidateRoundRepository candidateRounds;
    private InterviewEvaluationRepository evaluations;
    private InterviewerAssignmentRepository assignments;
    private OutboxEventRepository outboxEvents;
    private Phase3Access access;
    private InterviewEvaluationService service;

    @BeforeEach
    void setUp() {
        candidateRounds = mock(CandidateRoundRepository.class);
        evaluations = mock(InterviewEvaluationRepository.class);
        assignments = mock(InterviewerAssignmentRepository.class);
        outboxEvents = mock(OutboxEventRepository.class);
        access = mock(Phase3Access.class);
        service = new InterviewEvaluationService(candidateRounds, evaluations, assignments,
                outboxEvents, access, new ObjectMapper());
    }

    @Test
    void rejectsScoresOutsideRangeBeforeDatabaseAccess() {
        when(access.requireActiveMember(7L, 3L)).thenReturn(interviewer(7L));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submit(7L, 3L, 50L, request("100.01")));

        assertEquals(400, exception.getStatus());
        assertEquals("INVALID_EVALUATION", exception.getCode());
        verifyNoInteractions(candidateRounds, evaluations, assignments, outboxEvents);
    }

    @Test
    void rejectsAnInterviewerWithoutAnActiveAssignment() {
        when(access.requireActiveMember(7L, 3L)).thenReturn(interviewer(7L));
        when(candidateRounds.findByIdAndOrganizationId(50L, 3L))
                .thenReturn(Optional.of(candidateRound()));
        when(assignments.findActiveAssignmentsForUpdate(3L, 40L, 7L))
                .thenReturn(List.of());

        assertThrows(ForbiddenException.class,
                () -> service.submit(7L, 3L, 50L, request("80.00")));

        verify(candidateRounds, never()).findByIdAndOrganizationIdForUpdate(50L, 3L);
        verifyNoInteractions(evaluations, outboxEvents);
    }

    @Test
    void rejectsDuplicateSubmissionWithoutChangingTheExistingEvaluation() {
        when(access.requireActiveMember(7L, 3L)).thenReturn(interviewer(7L));
        when(candidateRounds.findByIdAndOrganizationId(50L, 3L))
                .thenReturn(Optional.of(candidateRound()));
        when(assignments.findActiveAssignmentsForUpdate(3L, 40L, 7L))
                .thenReturn(List.of(new InterviewerAssignment()));
        when(candidateRounds.findByIdAndOrganizationIdForUpdate(50L, 3L))
                .thenReturn(Optional.of(candidateRound()));
        when(evaluations.existsByOrganizationIdAndCandidateRoundIdAndInterviewerUserId(
                3L, 50L, 7L)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submit(7L, 3L, 50L, request("80.00")));

        assertEquals(409, exception.getStatus());
        assertEquals("EVALUATION_ALREADY_SUBMITTED", exception.getCode());
        verify(evaluations, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEvents);
    }

    private static Membership interviewer(long userId) {
        Membership membership = new Membership();
        membership.setAuthUserId(userId);
        membership.setRoles(java.util.Set.of(OrganizationRole.INTERVIEWER));
        return membership;
    }

    private static CandidateRound candidateRound() {
        CandidateRound candidateRound = new CandidateRound();
        candidateRound.setId(50L);
        candidateRound.setOrganizationId(3L);
        candidateRound.setInterviewRoundId(40L);
        return candidateRound;
    }

    private static SubmitInterviewEvaluationRequest request(String score) {
        return new SubmitInterviewEvaluationRequest(new BigDecimal(score),
                new BigDecimal("75.00"), new BigDecimal("70.00"),
                new BigDecimal("80.00"),
                com.queueguard.qms.domain.EvaluationRecommendation.RECOMMEND,
                "Structured feedback");
    }
}
