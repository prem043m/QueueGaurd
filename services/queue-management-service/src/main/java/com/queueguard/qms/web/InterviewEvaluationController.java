package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.qms.dto.Phase3Dtos.InterviewEvaluationView;
import com.queueguard.qms.dto.Phase3Dtos.SubmitInterviewEvaluationRequest;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.InterviewEvaluationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class InterviewEvaluationController {
    private final InterviewEvaluationService evaluationService;

    public InterviewEvaluationController(InterviewEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/candidate-rounds/{candidateRoundId}/evaluations")
    public ApiResponse<InterviewEvaluationView> submit(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long candidateRoundId,
            @Valid @RequestBody SubmitInterviewEvaluationRequest request) {
        return ApiResponse.ok("Interview evaluation submitted",
                evaluationService.submit(actorId, orgId, candidateRoundId, request));
    }

    @GetMapping("/candidate-rounds/{candidateRoundId}/evaluations")
    public ApiResponse<List<InterviewEvaluationView>> listForCandidateRound(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long candidateRoundId) {
        return ApiResponse.ok(evaluationService.listForCandidateRound(
                actorId, orgId, candidateRoundId));
    }

    @GetMapping("/evaluations/me")
    public ApiResponse<List<InterviewEvaluationView>> listMine(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId) {
        return ApiResponse.ok(evaluationService.listMine(actorId, orgId));
    }
}
