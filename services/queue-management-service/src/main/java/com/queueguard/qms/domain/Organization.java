package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "organizations")
public class Organization {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 160) private String name;
    @Column(name = "organization_type", nullable = false, length = 60) private String organizationType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OrganizationStatus status = OrganizationStatus.ACTIVE;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public String getName() { return name; } public void setName(String v) { name = v; }
    public String getOrganizationType() { return organizationType; } public void setOrganizationType(String v) { organizationType = v; }
    public OrganizationStatus getStatus() { return status; } public void setStatus(OrganizationStatus v) { status = v; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
