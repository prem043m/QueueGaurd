package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "placement_drives",
        uniqueConstraints = @UniqueConstraint(name = "uq_placement_drive_org_id",
                columnNames = {"organization_id", "id"}))
public class PlacementDrive {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(nullable = false, length = 255) private String name;
    @Column(length = 2000) private String description;
    @Column(name = "registration_capacity", nullable = false) private Integer registrationCapacity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private PlacementDriveStatus status = PlacementDriveStatus.DRAFT;
    @Column(name = "registration_opens_at") private Instant registrationOpensAt;
    @Column(name = "registration_closes_at") private Instant registrationClosesAt;
    @Column(name = "created_by", nullable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public Integer getRegistrationCapacity() { return registrationCapacity; } public void setRegistrationCapacity(Integer v) { registrationCapacity = v; }
    public PlacementDriveStatus getStatus() { return status; } public void setStatus(PlacementDriveStatus v) { status = v; }
    public Instant getRegistrationOpensAt() { return registrationOpensAt; } public void setRegistrationOpensAt(Instant v) { registrationOpensAt = v; }
    public Instant getRegistrationClosesAt() { return registrationClosesAt; } public void setRegistrationClosesAt(Instant v) { registrationClosesAt = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
