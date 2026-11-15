package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.qms.domain.CandidateRoundResult;
import com.queueguard.qms.dto.Phase3Dtos.CandidateRoundDecisionRequest;
import com.queueguard.qms.dto.Phase3Dtos.CandidateRoundView;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.CandidateRoundService;
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
public class CandidateRoundController {
    private final CandidateRoundService candidateRoundService;

    public CandidateRoundController(CandidateRoundService candidateRoundService) {
        this.candidateRoundService = candidateRoundService;
    }

    @PostMapping("/rounds/{roundId}/applications/{applicationId}/register")
    public ApiResponse<CandidateRoundView> register(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId,
            @PathVariable Long applicationId) {
        return ApiResponse.ok("Candidate registered for interview round",
                candidateRoundService.register(actorId, orgId, applicationId, roundId));
    }

    @GetMapping("/rounds/{roundId}/candidates")
    public ApiResponse<List<CandidateRoundView>> listForRound(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId) {
        return ApiResponse.ok(candidateRoundService.listForRound(actorId, orgId, roundId));
    }

    @GetMapping("/applications/{applicationId}/rounds")
    public ApiResponse<List<CandidateRoundView>> listForApplication(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId) {
        return ApiResponse.ok(
                candidateRoundService.listForApplication(actorId, orgId, applicationId));
    }

    @GetMapping("/rounds/{roundId}/applications/{applicationId}")
    public ApiResponse<CandidateRoundView> get(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId,
            @PathVariable Long applicationId) {
        return ApiResponse.ok(
                candidateRoundService.get(actorId, orgId, applicationId, roundId));
    }

    @PostMapping("/applications/{applicationId}/rounds/{roundId}/qualify")
    public ApiResponse<CandidateRoundView> qualify(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @PathVariable Long roundId,
            @Valid @RequestBody(required = false) CandidateRoundDecisionRequest request) {
        return decision(actorId, orgId, applicationId, roundId, CandidateRoundResult.QUALIFIED,
                request);
    }

    @PostMapping("/applications/{applicationId}/rounds/{roundId}/disqualify")
    public ApiResponse<CandidateRoundView> disqualify(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @PathVariable Long roundId,
            @Valid @RequestBody(required = false) CandidateRoundDecisionRequest request) {
        return decision(actorId, orgId, applicationId, roundId, CandidateRoundResult.DISQUALIFIED,
                request);
    }

    @PostMapping("/applications/{applicationId}/rounds/{roundId}/select")
    public ApiResponse<CandidateRoundView> select(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @PathVariable Long roundId,
            @Valid @RequestBody(required = false) CandidateRoundDecisionRequest request) {
        return decision(actorId, orgId, applicationId, roundId, CandidateRoundResult.SELECTED,
                request);
    }

    @PostMapping("/applications/{applicationId}/rounds/{roundId}/hold")
    public ApiResponse<CandidateRoundView> hold(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @PathVariable Long roundId,
            @Valid @RequestBody(required = false) CandidateRoundDecisionRequest request) {
        return decision(actorId, orgId, applicationId, roundId, CandidateRoundResult.HOLD,
                request);
    }

    @PostMapping("/applications/{applicationId}/rounds/{roundId}/progress")
    public ApiResponse<CandidateRoundView> progress(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @PathVariable Long roundId) {
        return ApiResponse.ok("Candidate progressed to the next interview round",
                candidateRoundService.progress(actorId, orgId, applicationId, roundId));
    }

    private ApiResponse<CandidateRoundView> decision(
            Long actorId, Long organizationId, Long applicationId, Long roundId,
            CandidateRoundResult result, CandidateRoundDecisionRequest request) {
        return ApiResponse.ok("Interview round outcome recorded",
                candidateRoundService.decide(actorId, organizationId, applicationId, roundId,
                        result, request == null ? null : request.reason()));
    }
}
