package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.dto.Phase3Dtos.CandidateProfileRequest;
import com.queueguard.qms.dto.Phase3Dtos.CandidateProfileView;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.CandidateProfileService;
import com.queueguard.qms.service.Phase3Access;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/candidates/profiles")
public class CandidateProfileController {
    private final CandidateProfileService profileService;
    private final Phase3Access access;

    public CandidateProfileController(
            CandidateProfileService profileService, Phase3Access access) {
        this.profileService = profileService;
        this.access = access;
    }

    @PostMapping
    public ApiResponse<CandidateProfileView> create(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @Valid @RequestBody CandidateProfileRequest request) {
        return ApiResponse.ok("Candidate profile created", CandidateProfileView.from(
                profileService.create(actorId, orgId, request.studentName(), request.email(),
                        request.registrationNumber(), request.phone(), request.department(),
                        request.graduationYear(), request.cgpa(), request.resumeReference(),
                        request.skills())));
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ApiResponse<CandidateProfileView> getMine(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId) {
        access.requireRole(actorId, orgId, OrganizationRole.MEMBER);
        return ApiResponse.ok(CandidateProfileView.from(
                profileService.getForUser(actorId, orgId, actorId)));
    }

    @GetMapping("/{profileId}")
    @Transactional(readOnly = true)
    public ApiResponse<CandidateProfileView> get(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long profileId) {
        requireMemberOrManager(actorId, orgId);
        return ApiResponse.ok(CandidateProfileView.from(
                profileService.get(actorId, orgId, profileId)));
    }

    @PutMapping("/{profileId}")
    @Transactional
    public ApiResponse<CandidateProfileView> update(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long profileId,
            @Valid @RequestBody CandidateProfileRequest request) {
        requireMemberOrManager(actorId, orgId);
        return ApiResponse.ok("Candidate profile updated", CandidateProfileView.from(
                profileService.update(actorId, orgId, profileId, request.studentName(),
                        request.email(), request.registrationNumber(), request.phone(),
                        request.department(), request.graduationYear(), request.cgpa(),
                        request.resumeReference(), request.skills())));
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
