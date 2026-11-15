package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "memberships", uniqueConstraints = @UniqueConstraint(name = "uq_membership_org_user", columnNames = {"organization_id", "auth_user_id"}))
public class Membership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "organization_id") private Organization organization;
    @Column(name = "auth_user_id", nullable = false) private Long authUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MembershipStatus status = MembershipStatus.ACTIVE;
    @ElementCollection(targetClass = OrganizationRole.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_roles", joinColumns = @JoinColumn(name = "membership_id"))
    @Enumerated(EnumType.STRING) @Column(name = "role", nullable = false, length = 30)
    private Set<OrganizationRole> roles = EnumSet.noneOf(OrganizationRole.class);
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public Organization getOrganization() { return organization; } public void setOrganization(Organization v) { organization = v; }
    public Long getAuthUserId() { return authUserId; } public void setAuthUserId(Long v) { authUserId = v; }
    public MembershipStatus getStatus() { return status; } public void setStatus(MembershipStatus v) { status = v; }
    public Set<OrganizationRole> getRoles() { return roles; } public void setRoles(Set<OrganizationRole> v) { roles = v.isEmpty() ? EnumSet.noneOf(OrganizationRole.class) : EnumSet.copyOf(v); }
    public Instant getCreatedAt() { return createdAt; }
}
