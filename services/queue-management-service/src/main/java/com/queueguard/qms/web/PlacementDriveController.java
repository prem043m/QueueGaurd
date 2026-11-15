package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.dto.Phase3Dtos.CapacityRequest;
import com.queueguard.qms.dto.Phase3Dtos.PlacementDriveRequest;
import com.queueguard.qms.dto.Phase3Dtos.PlacementDriveView;
import com.queueguard.qms.dto.Phase3Dtos.UpdatePlacementDriveRequest;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.Phase3Access;
import com.queueguard.qms.service.PlacementDriveService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/drives")
public class PlacementDriveController {
    private final PlacementDriveService driveService;
    private final Phase3Access access;

    public PlacementDriveController(PlacementDriveService driveService, Phase3Access access) {
        this.driveService = driveService;
        this.access = access;
    }

    @PostMapping
    public ApiResponse<PlacementDriveView> create(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @Valid @RequestBody PlacementDriveRequest request) {
        return ApiResponse.ok("Placement drive created", PlacementDriveView.from(
                driveService.create(actorId, orgId, request.name(), request.description(),
                        request.registrationCapacity(), request.registrationOpensAt(),
                        request.registrationClosesAt())));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<List<PlacementDriveView>> list(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @RequestParam(required = false) PlacementDriveStatus status) {
        requireMemberOrManager(actorId, orgId);
        List<PlacementDrive> drives = status == null
                ? driveService.list(actorId, orgId)
                : driveService.listByStatus(actorId, orgId, status);
        return ApiResponse.ok(drives.stream().map(PlacementDriveView::from).toList());
    }

    @GetMapping("/{driveId}")
    @Transactional(readOnly = true)
    public ApiResponse<PlacementDriveView> get(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        requireMemberOrManager(actorId, orgId);
        return ApiResponse.ok(PlacementDriveView.from(
                driveService.get(actorId, orgId, driveId)));
    }

    @PutMapping("/{driveId}")
    public ApiResponse<PlacementDriveView> update(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId,
            @Valid @RequestBody UpdatePlacementDriveRequest request) {
        return ApiResponse.ok("Placement drive updated", PlacementDriveView.from(
                driveService.updateDetails(actorId, orgId, driveId, request.name(),
                        request.description(), request.registrationOpensAt(),
                        request.registrationClosesAt())));
    }

    @PatchMapping("/{driveId}/capacity")
    public ApiResponse<PlacementDriveView> updateCapacity(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId,
            @Valid @RequestBody CapacityRequest request) {
        return ApiResponse.ok("Placement drive capacity updated", PlacementDriveView.from(
                driveService.updateCapacity(actorId, orgId, driveId, request.capacity())));
    }

    @PostMapping("/{driveId}/publish")
    public ApiResponse<PlacementDriveView> publish(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok("Placement drive published", PlacementDriveView.from(
                driveService.publish(actorId, orgId, driveId)));
    }

    @PostMapping("/{driveId}/open")
    public ApiResponse<PlacementDriveView> openRegistration(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok("Registration opened", PlacementDriveView.from(
                driveService.openRegistration(actorId, orgId, driveId)));
    }

    @PostMapping("/{driveId}/close")
    public ApiResponse<PlacementDriveView> closeRegistration(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok("Registration closed", PlacementDriveView.from(
                driveService.closeRegistration(actorId, orgId, driveId)));
    }

    @PostMapping("/{driveId}/start")
    public ApiResponse<PlacementDriveView> start(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok("Placement drive started", PlacementDriveView.from(
                driveService.start(actorId, orgId, driveId)));
    }

    @PostMapping("/{driveId}/complete")
    public ApiResponse<PlacementDriveView> complete(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok("Placement drive completed", PlacementDriveView.from(
                driveService.complete(actorId, orgId, driveId)));
    }

    @PostMapping("/{driveId}/cancel")
    public ApiResponse<PlacementDriveView> cancel(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok("Placement drive cancelled", PlacementDriveView.from(
                driveService.cancel(actorId, orgId, driveId)));
    }

    private void requireMemberOrManager(Long actorId, Long organizationId) {
        Membership membership = access.requireActiveMember(actorId, organizationId);
        if (!membership.getRoles().contains(OrganizationRole.MEMBER)
                && !membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                && !membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER)) {
            throw new ForbiddenException("Organization member or drive manager role required");
        }
    }

}
