package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.CandidateProfile;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.repo.CandidateProfileRepository;
import java.math.BigDecimal;
import java.time.Year;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateProfileService {
    private final CandidateProfileRepository profileRepository;
    private final Phase3Access access;

    public CandidateProfileService(
            CandidateProfileRepository profileRepository, Phase3Access access) {
        this.profileRepository = profileRepository;
        this.access = access;
    }

    @Transactional
    public CandidateProfile create(
            Long actorId, Long organizationId, String studentName, String email,
            String registrationNumber, String phone, String department,
            Integer graduationYear, BigDecimal cgpa, String resumeReference, String skills) {
        access.requireRole(actorId, organizationId, OrganizationRole.MEMBER);
        validateProfile(studentName, email, graduationYear, cgpa);
        if (profileRepository.existsByUserIdAndOrganizationId(actorId, organizationId)) {
            throw conflict("CANDIDATE_PROFILE_EXISTS",
                    "A candidate profile already exists for this organization member");
        }

        CandidateProfile profile = new CandidateProfile();
        profile.setOrganizationId(organizationId);
        profile.setUserId(actorId);
        applyProfileFields(profile, studentName, email, registrationNumber, phone,
                department, graduationYear, cgpa, resumeReference, skills);
        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public CandidateProfile get(Long actorId, Long organizationId, Long profileId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateProfile profile = requireProfile(profileId, organizationId);
        requireOwnerOrManager(actor, profile);
        return profile;
    }

    @Transactional(readOnly = true)
    public CandidateProfile getForUser(Long actorId, Long organizationId, Long userId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        if (!actorId.equals(userId) && !isManager(actor)) {
            throw new ForbiddenException("Candidates may access only their own profile");
        }
        return profileRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate profile not found"));
    }

    @Transactional
    public CandidateProfile update(
            Long actorId, Long organizationId, Long profileId, String studentName,
            String email, String registrationNumber, String phone, String department,
            Integer graduationYear, BigDecimal cgpa, String resumeReference, String skills) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateProfile profile = requireProfile(profileId, organizationId);
        requireOwnerOrManager(actor, profile);
        validateProfile(studentName, email, graduationYear, cgpa);
        applyProfileFields(profile, studentName, email, registrationNumber, phone,
                department, graduationYear, cgpa, resumeReference, skills);
        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public boolean ownsProfile(Long actorId, Long organizationId, Long profileId) {
        access.requireActiveMember(actorId, organizationId);
        return profileRepository.findByIdAndOrganizationId(profileId, organizationId)
                .map(profile -> profile.getUserId().equals(actorId))
                .orElse(false);
    }

    private CandidateProfile requireProfile(Long profileId, Long organizationId) {
        return profileRepository.findByIdAndOrganizationId(profileId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate profile not found"));
    }

    private static void requireOwnerOrManager(Membership actor, CandidateProfile profile) {
        if (!actor.getAuthUserId().equals(profile.getUserId()) && !isManager(actor)) {
            throw new ForbiddenException("Candidate profile belongs to another user");
        }
    }

    private static boolean isManager(Membership membership) {
        return membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                || membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
    }

    private static void validateProfile(
            String studentName, String email, Integer graduationYear, BigDecimal cgpa) {
        if (studentName == null || studentName.isBlank()) {
            throw conflict("INVALID_CANDIDATE_NAME", "Candidate name is required");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw conflict("INVALID_CANDIDATE_EMAIL", "A valid candidate email is required");
        }
        if (graduationYear != null
                && (graduationYear < 1900 || graduationYear > Year.now().getValue() + 10)) {
            throw conflict("INVALID_GRADUATION_YEAR", "Graduation year is outside the supported range");
        }
        if (cgpa != null && (cgpa.signum() < 0 || cgpa.compareTo(BigDecimal.TEN) > 0)) {
            throw conflict("INVALID_CGPA", "CGPA must be between 0 and 10");
        }
    }

    private static void applyProfileFields(
            CandidateProfile profile, String studentName, String email,
            String registrationNumber, String phone, String department,
            Integer graduationYear, BigDecimal cgpa, String resumeReference, String skills) {
        profile.setStudentName(studentName.trim());
        profile.setEmail(email.trim());
        profile.setRegistrationNumber(trimToNull(registrationNumber));
        profile.setPhone(trimToNull(phone));
        profile.setDepartment(trimToNull(department));
        profile.setGraduationYear(graduationYear);
        profile.setCgpa(cgpa);
        profile.setResumeReference(trimToNull(resumeReference));
        profile.setSkills(trimToNull(skills));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(400, code, message);
    }
}
