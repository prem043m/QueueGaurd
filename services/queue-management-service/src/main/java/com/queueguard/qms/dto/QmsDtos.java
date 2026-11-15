package com.queueguard.qms.dto;
import com.queueguard.qms.domain.*;
import jakarta.validation.constraints.*;
import java.util.Set;
public final class QmsDtos {
 private QmsDtos() {}
 public record CreateOrganizationRequest(@NotBlank @Size(min=2,max=160) String name, @NotBlank @Size(max=60) String organizationType) {}
 public record AddMembershipRequest(@NotNull @Positive Long userId, @NotEmpty Set<OrganizationRole> roles) {}
 public record UpdateRolesRequest(@NotEmpty Set<OrganizationRole> roles) {}
 public record UpdateMembershipStatusRequest(@NotNull MembershipStatus status) {}
 public record OrganizationView(Long id,String name,String organizationType,OrganizationStatus status,String createdAt,String updatedAt) {}
 public record MembershipView(Long id,Long organizationId,Long userId,MembershipStatus status,Set<OrganizationRole> roles,String createdAt) {}
}
