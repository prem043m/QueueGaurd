package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.ApplicationStatus;
import com.queueguard.qms.domain.CandidateApplication;
import com.queueguard.qms.domain.CandidateProfile;
import com.queueguard.qms.domain.EligibilityStatus;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.CandidateProfileRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateApplicationService {
    private final CandidateApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final PlacementDriveRepository driveRepository;
    private final Phase3Access access;

    public CandidateApplicationService(
            CandidateApplicationRepository applicationRepository,
            CandidateProfileRepository profileRepository,
            PlacementDriveRepository driveRepository,
            Phase3Access access) {
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.driveRepository = driveRepository;
        this.access = access;
    }

    @Transactional
    public CandidateApplication submit(Long actorId, Long organizationId, Long profileId, Long driveId) {
        access.requireRole(actorId, organizationId, OrganizationRole.MEMBER);
        CandidateProfile profile = profileRepository.findByIdAndOrganizationId(profileId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate profile not found"));
        if (!profile.getUserId().equals(actorId)) {
            throw new ForbiddenException("Candidates may apply only with their own profile");
        }
        PlacementDrive drive = driveRepository.findByIdAndOrganizationIdForUpdate(
                driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
        validateRegistrationOpen(drive, Instant.now());
        if (applicationRepository.existsByOrganizationIdAndPlacementDriveIdAndCandidateProfileId(
                organizationId, driveId, profileId)) {
            throw conflict("DUPLICATE_APPLICATION", "Candidate has already applied to this drive");
        }

        long applicationNumber =
                applicationRepository.allocateNextApplicationNumber(organizationId, driveId);
        CandidateApplication application = new CandidateApplication();
        application.setOrganizationId(organizationId);
        application.setPlacementDriveId(driveId);
        application.setApplicationNumber(applicationNumber);
        application.setCandidateProfileId(profileId);
        application.setStudentUserId(actorId);
        application.setEligibilityStatus(EligibilityStatus.PENDING);
        application.setApplicationStatus(ApplicationStatus.SUBMITTED);
        return applicationRepository.save(application);
    }

    @Transactional(readOnly = true)
    public CandidateApplication get(Long actorId, Long organizationId, Long applicationId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateApplication application = requireApplication(applicationId, organizationId);
        requireApplicantOrManager(actor, application);
        return application;
    }

    @Transactional(readOnly = true)
    public List<CandidateApplication> listByDrive(
            Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        if (driveRepository.findByIdAndOrganizationId(driveId, organizationId).isEmpty()) {
            throw new NotFoundException("Placement drive not found");
        }
        return applicationRepository
                .findByOrganizationIdAndPlacementDriveIdOrderByApplicationNumber(
                        organizationId, driveId);
    }

    @Transactional(readOnly = true)
    public List<CandidateApplication> listForCandidate(
            Long actorId, Long organizationId, Long profileId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateProfile profile = profileRepository.findByIdAndOrganizationId(profileId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate profile not found"));
        if (!actorId.equals(profile.getUserId()) && !isManager(actor)) {
            throw new ForbiddenException("Candidates may view only their own applications");
        }
        return applicationRepository
                .findByOrganizationIdAndCandidateProfileIdOrderByAppliedAtDesc(
                        organizationId, profileId);
    }

    @Transactional
    public CandidateApplication updateEligibility(
            Long actorId, Long organizationId, Long applicationId, EligibilityStatus nextStatus,
            String reason) {
        access.requireDriveManager(actorId, organizationId);
        CandidateApplication application = lockApplication(applicationId, organizationId);
        if (application.getApplicationStatus() != ApplicationStatus.SUBMITTED
                || application.getEligibilityStatus() != EligibilityStatus.PENDING
                || (nextStatus != EligibilityStatus.ELIGIBLE
                    && nextStatus != EligibilityStatus.INELIGIBLE)) {
            throw conflict("INVALID_ELIGIBILITY_TRANSITION",
                    "Eligibility may transition only from PENDING while the application is SUBMITTED");
        }
        application.setEligibilityStatus(nextStatus);
        application.setEligibilityReason(trimToNull(reason));
        return applicationRepository.save(application);
    }

    @Transactional
    public CandidateApplication accept(
            Long actorId, Long organizationId, Long applicationId) {
        access.requireDriveManager(actorId, organizationId);
        Long driveId = applicationRepository
                .findPlacementDriveIdByIdAndOrganizationId(applicationId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate application not found"));
        PlacementDrive drive = driveRepository
                .findByIdAndOrganizationIdForUpdate(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
        CandidateApplication application = lockApplication(applicationId, organizationId);
        if (!application.getPlacementDriveId().equals(drive.getId())) {
            throw new NotFoundException("Candidate application not found");
        }
        if (application.getApplicationStatus() == ApplicationStatus.ACCEPTED) {
            return application;
        }
        if (drive.getStatus() == PlacementDriveStatus.CANCELLED
                || drive.getStatus() == PlacementDriveStatus.COMPLETED) {
            throw conflict("DRIVE_NOT_ACCEPTING_CANDIDATES",
                    "Applications cannot be accepted for a completed or cancelled drive");
        }
        if (application.getEligibilityStatus() != EligibilityStatus.ELIGIBLE) {
            throw conflict("APPLICATION_NOT_ELIGIBLE", "Only eligible applications can be accepted");
        }
        if (application.getApplicationStatus() != ApplicationStatus.SUBMITTED) {
            throw conflict("INVALID_APPLICATION_TRANSITION",
                    "Only submitted applications can be accepted");
        }
        long reserved = applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        organizationId, driveId);
        if (reserved >= drive.getRegistrationCapacity()) {
            throw conflict("DRIVE_CAPACITY_EXCEEDED", "Placement drive capacity is full");
        }
        application.setApplicationStatus(ApplicationStatus.ACCEPTED);
        application.setCapacityReservedAt(Instant.now());
        return applicationRepository.save(application);
    }

    @Transactional
    public CandidateApplication reject(
            Long actorId, Long organizationId, Long applicationId) {
        access.requireDriveManager(actorId, organizationId);
        CandidateApplication application = lockApplication(applicationId, organizationId);
        if (application.getApplicationStatus() != ApplicationStatus.SUBMITTED) {
            throw conflict("INVALID_APPLICATION_TRANSITION",
                    "Only submitted applications can be rejected");
        }
        application.setApplicationStatus(ApplicationStatus.REJECTED);
        application.setCapacityReservedAt(null);
        return applicationRepository.save(application);
    }

    @Transactional
    public CandidateApplication withdraw(
            Long actorId, Long organizationId, Long applicationId, String reason) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateApplication application = lockApplication(applicationId, organizationId);
        requireApplicantOrManager(actor, application);
        if (application.getApplicationStatus() == ApplicationStatus.WITHDRAWN) {
            return application;
        }
        if (application.getApplicationStatus() != ApplicationStatus.SUBMITTED
                && application.getApplicationStatus() != ApplicationStatus.ACCEPTED) {
            throw conflict("INVALID_APPLICATION_TRANSITION",
                    "Only submitted or accepted applications can be withdrawn");
        }
        application.setApplicationStatus(ApplicationStatus.WITHDRAWN);
        application.setWithdrawalReason(trimToNull(reason));
        return applicationRepository.save(application);
    }

    @Transactional(readOnly = true)
    public long countReserved(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        return applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        organizationId, driveId);
    }

    private CandidateApplication requireApplication(Long applicationId, Long organizationId) {
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate application not found"));
    }

    private CandidateApplication lockApplication(Long applicationId, Long organizationId) {
        return applicationRepository.findByIdAndOrganizationIdForUpdate(applicationId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate application not found"));
    }

    private void requireApplicantOrManager(Membership actor, CandidateApplication application) {
        if (!actor.getAuthUserId().equals(application.getStudentUserId()) && !isManager(actor)) {
            throw new ForbiddenException("Candidate application belongs to another user");
        }
    }

    private static void validateRegistrationOpen(PlacementDrive drive, Instant now) {
        if (drive.getStatus() != PlacementDriveStatus.REGISTRATION_OPEN) {
            throw conflict("REGISTRATION_CLOSED", "The placement drive is not open for registration");
        }
        if (drive.getRegistrationOpensAt() != null && now.isBefore(drive.getRegistrationOpensAt())) {
            throw conflict("REGISTRATION_NOT_OPEN", "The registration opening time has not arrived");
        }
        if (drive.getRegistrationClosesAt() != null
                && !now.isBefore(drive.getRegistrationClosesAt())) {
            throw conflict("REGISTRATION_CLOSED", "The registration window has closed");
        }
    }

    private static boolean isManager(Membership membership) {
        return membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                || membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }
}
