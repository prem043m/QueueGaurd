package com.queueguard.qms.service;

import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class Phase3Access {
    private static final Set<OrganizationRole> DRIVE_MANAGERS =
            Set.of(OrganizationRole.ORG_ADMIN, OrganizationRole.QUEUE_MANAGER);

    private final TenantService tenantService;

    public Phase3Access(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    public Membership requireActiveMember(Long actorId, Long organizationId) {
        return tenantService.active(actorId, organizationId);
    }

    public Membership requireDriveManager(Long actorId, Long organizationId) {
        Membership membership = requireActiveMember(actorId, organizationId);
        if (membership.getRoles().stream().noneMatch(DRIVE_MANAGERS::contains)) {
            throw new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required");
        }
        return membership;
    }

    public Membership requireRole(
            Long actorId, Long organizationId, OrganizationRole requiredRole) {
        Membership membership = requireActiveMember(actorId, organizationId);
        if (!membership.getRoles().contains(requiredRole)) {
            throw new ForbiddenException(requiredRole + " role required");
        }
        return membership;
    }
}
