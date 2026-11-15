package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.dto.Phase3Dtos.CandidateApplicationView;
import com.queueguard.qms.dto.Phase3Dtos.EligibilityRequest;
import com.queueguard.qms.dto.Phase3Dtos.SubmitApplicationRequest;
import com.queueguard.qms.dto.Phase3Dtos.WithdrawApplicationRequest;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.CandidateApplicationService;
import com.queueguard.qms.service.CandidateProfileService;
import com.queueguard.qms.service.Phase3Access;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class CandidateApplicationController {
    private final CandidateApplicationService applicationService;
    private final CandidateProfileService profileService;
    private final Phase3Access access;

    public CandidateApplicationController(
            CandidateApplicationService applicationService,
            CandidateProfileService profileService,
            Phase3Access access) {
        this.applicationService = applicationService;
        this.profileService = profileService;
        this.access = access;
    }

    @PostMapping("/drives/{driveId}/applications")
    public ApiResponse<CandidateApplicationView> submit(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId,
            @Valid @RequestBody SubmitApplicationRequest request) {
        return ApiResponse.ok("Application submitted", CandidateApplicationView.from(
                applicationService.submit(actorId, orgId,
                        request.candidateProfileId(), driveId)));
    }

    @GetMapping("/applications/me")
    @Transactional(readOnly = true)
    public ApiResponse<List<CandidateApplicationView>> listMine(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId) {
        access.requireRole(actorId, orgId, OrganizationRole.MEMBER);
        Long profileId = profileService.getForUser(actorId, orgId, actorId).getId();
        return ApiResponse.ok(applicationService.listForCandidate(actorId, orgId, profileId)
                .stream().map(CandidateApplicationView::from).toList());
    }

    @GetMapping("/drives/{driveId}/applications")
    public ApiResponse<List<CandidateApplicationView>> listByDrive(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok(applicationService.listByDrive(actorId, orgId, driveId)
                .stream().map(CandidateApplicationView::from).toList());
    }

    @GetMapping("/applications/{applicationId}")
    @Transactional(readOnly = true)
    public ApiResponse<CandidateApplicationView> get(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId) {
        requireMemberOrManager(actorId, orgId);
        return ApiResponse.ok(CandidateApplicationView.from(
                applicationService.get(actorId, orgId, applicationId)));
    }

    @PatchMapping("/applications/{applicationId}/eligibility")
    public ApiResponse<CandidateApplicationView> updateEligibility(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @Valid @RequestBody EligibilityRequest request) {
        return ApiResponse.ok("Application eligibility updated", CandidateApplicationView.from(
                applicationService.updateEligibility(actorId, orgId, applicationId,
                        request.eligibilityStatus(), request.reason())));
    }

    @PostMapping("/applications/{applicationId}/accept")
    public ApiResponse<CandidateApplicationView> accept(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId) {
        return ApiResponse.ok("Application accepted", CandidateApplicationView.from(
                applicationService.accept(actorId, orgId, applicationId)));
    }

    @PostMapping("/applications/{applicationId}/reject")
    public ApiResponse<CandidateApplicationView> reject(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId) {
        return ApiResponse.ok("Application rejected", CandidateApplicationView.from(
                applicationService.reject(actorId, orgId, applicationId)));
    }

    @PostMapping("/applications/{applicationId}/withdraw")
    @Transactional
    public ApiResponse<CandidateApplicationView> withdraw(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long applicationId,
            @RequestBody(required = false) @Valid WithdrawApplicationRequest request) {
        requireMemberOrManager(actorId, orgId);
        return ApiResponse.ok("Application withdrawn", CandidateApplicationView.from(
                applicationService.withdraw(actorId, orgId, applicationId,
                        request == null ? null : request.reason())));
    }

    private void requireMemberOrManager(Long actorId, Long organizationId) {
        Membership membership = access.requireActiveMember(actorId, organizationId);
        if (!membership.getRoles().contains(OrganizationRole.MEMBER)
                && !membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                && !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("Candidate or drive manager role required");
        }
    }
}
