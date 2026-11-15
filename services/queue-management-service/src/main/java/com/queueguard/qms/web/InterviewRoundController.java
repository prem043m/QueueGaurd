package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.InterviewRoundStatus;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.dto.Phase3Dtos.InterviewRoundRequest;
import com.queueguard.qms.dto.Phase3Dtos.InterviewRoundView;
import com.queueguard.qms.dto.Phase3Dtos.UpdateInterviewRoundRequest;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.Phase3Access;
import com.queueguard.qms.service.InterviewRoundService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class InterviewRoundController {
    private final InterviewRoundService roundService;
    private final Phase3Access access;

    public InterviewRoundController(InterviewRoundService roundService, Phase3Access access) {
        this.roundService = roundService;
        this.access = access;
    }

    @PostMapping("/drives/{driveId}/rounds")
    public ApiResponse<InterviewRoundView> create(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId,
            @Valid @RequestBody InterviewRoundRequest request) {
        return ApiResponse.ok("Interview round created", InterviewRoundView.from(
                roundService.create(actorId, orgId, driveId, request.queueConfigurationId(),
                        request.roundNumber(), request.name(), request.description(),
                        request.roundType(), request.scheduledStartAt(),
                        request.scheduledEndAt(), request.qualificationCriteria())));
    }

    @GetMapping("/drives/{driveId}/rounds")
    @Transactional(readOnly = true)
    public ApiResponse<List<InterviewRoundView>> listByDrive(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        requireMemberOrManager(actorId, orgId);
        return ApiResponse.ok(roundService.listByDrive(actorId, orgId, driveId)
                .stream().map(InterviewRoundView::from).toList());
    }

    @GetMapping("/rounds")
    public ApiResponse<List<InterviewRoundView>> listByStatus(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @RequestParam InterviewRoundStatus status) {
        return ApiResponse.ok(roundService.listByStatus(actorId, orgId, status)
                .stream().map(InterviewRoundView::from).toList());
    }

    @GetMapping("/rounds/{roundId}")
    @Transactional(readOnly = true)
    public ApiResponse<InterviewRoundView> get(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId) {
        requireManagerOrInterviewer(actorId, orgId);
        return ApiResponse.ok(InterviewRoundView.from(
                roundService.get(actorId, orgId, roundId)));
    }

    @PutMapping("/rounds/{roundId}")
    public ApiResponse<InterviewRoundView> update(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId,
            @Valid @RequestBody UpdateInterviewRoundRequest request) {
        return ApiResponse.ok("Interview round updated", InterviewRoundView.from(
                roundService.updateDetails(actorId, orgId, roundId, request.name(),
                        request.description(), request.roundType(),
                        request.scheduledStartAt(), request.scheduledEndAt(),
                        request.qualificationCriteria())));
    }

    @PostMapping("/rounds/{roundId}/open")
    public ApiResponse<InterviewRoundView> open(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId) {
        return ApiResponse.ok("Interview round opened", InterviewRoundView.from(
                roundService.open(actorId, orgId, roundId)));
    }

    @PostMapping("/rounds/{roundId}/pause")
    public ApiResponse<InterviewRoundView> pause(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId) {
        return ApiResponse.ok("Interview round paused", InterviewRoundView.from(
                roundService.pause(actorId, orgId, roundId)));
    }

    @PostMapping("/rounds/{roundId}/close")
    public ApiResponse<InterviewRoundView> close(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId) {
        return ApiResponse.ok("Interview round closed", InterviewRoundView.from(
                roundService.close(actorId, orgId, roundId)));
    }

    private void requireMemberOrManager(Long actorId, Long organizationId) {
        Membership membership = access.requireActiveMember(actorId, organizationId);
        if (!membership.getRoles().contains(OrganizationRole.MEMBER)
                && !membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                && !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("Organization member or drive manager role required");
        }
    }

    private void requireManagerOrInterviewer(Long actorId, Long organizationId) {
        Membership membership = access.requireActiveMember(actorId, organizationId);
        if (!membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                && !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)
                && !membership.getRoles().contains(OrganizationRole.INTERVIEWER)) {
            throw new ForbiddenException("Drive manager or interviewer role required");
        }
    }
}
