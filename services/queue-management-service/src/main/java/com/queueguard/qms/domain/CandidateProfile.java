package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candidate_profiles",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_candidate_profile_org_user",
                    columnNames = {"organization_id", "user_id"}),
            @UniqueConstraint(name = "uq_candidate_profile_org_id_user",
                    columnNames = {"organization_id", "id", "user_id"})
        })
public class CandidateProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "student_name", nullable = false, length = 255) private String studentName;
    @Column(name = "registration_number", length = 100) private String registrationNumber;
    @Column(nullable = false, length = 320) private String email;
    @Column(length = 50) private String phone;
    @Column(length = 255) private String department;
    @Column(name = "graduation_year") private Integer graduationYear;
    @Column(precision = 4, scale = 2) private BigDecimal cgpa;
    @Column(name = "resume_reference", length = 1000) private String resumeReference;
    @Column(columnDefinition = "TEXT") private String skills;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getStudentName() { return studentName; } public void setStudentName(String v) { studentName = v; }
    public String getRegistrationNumber() { return registrationNumber; } public void setRegistrationNumber(String v) { registrationNumber = v; }
    public String getEmail() { return email; } public void setEmail(String v) { email = v; }
    public String getPhone() { return phone; } public void setPhone(String v) { phone = v; }
    public String getDepartment() { return department; } public void setDepartment(String v) { department = v; }
    public Integer getGraduationYear() { return graduationYear; } public void setGraduationYear(Integer v) { graduationYear = v; }
    public BigDecimal getCgpa() { return cgpa; } public void setCgpa(BigDecimal v) { cgpa = v; }
    public String getResumeReference() { return resumeReference; } public void setResumeReference(String v) { resumeReference = v; }
    public String getSkills() { return skills; } public void setSkills(String v) { skills = v; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
