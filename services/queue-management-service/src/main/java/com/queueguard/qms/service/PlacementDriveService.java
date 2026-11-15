package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlacementDriveService {
    private final PlacementDriveRepository driveRepository;
    private final CandidateApplicationRepository applicationRepository;
    private final Phase3Access access;

    public PlacementDriveService(
            PlacementDriveRepository driveRepository,
            CandidateApplicationRepository applicationRepository,
            Phase3Access access) {
        this.driveRepository = driveRepository;
        this.applicationRepository = applicationRepository;
        this.access = access;
    }

    @Transactional
    public PlacementDrive create(
            Long actorId, Long organizationId, String name, String description,
            int registrationCapacity, Instant registrationOpensAt,
            Instant registrationClosesAt) {
        access.requireDriveManager(actorId, organizationId);
        validateName(name);
        validateCapacity(registrationCapacity);
        validateRegistrationWindow(registrationOpensAt, registrationClosesAt);

        PlacementDrive drive = new PlacementDrive();
        drive.setOrganizationId(organizationId);
        drive.setCreatedBy(actorId);
        drive.setName(name.trim());
        drive.setDescription(trimToNull(description));
        drive.setRegistrationCapacity(registrationCapacity);
        drive.setRegistrationOpensAt(registrationOpensAt);
        drive.setRegistrationClosesAt(registrationClosesAt);
        return driveRepository.save(drive);
    }

    @Transactional(readOnly = true)
    public PlacementDrive get(Long actorId, Long organizationId, Long driveId) {
        access.requireActiveMember(actorId, organizationId);
        return requireDrive(organizationId, driveId);
    }

    @Transactional(readOnly = true)
    public List<PlacementDrive> list(Long actorId, Long organizationId) {
        access.requireActiveMember(actorId, organizationId);
        return driveRepository.findByOrganizationIdOrderById(organizationId);
    }

    @Transactional(readOnly = true)
    public List<PlacementDrive> listByStatus(
            Long actorId, Long organizationId, PlacementDriveStatus status) {
        access.requireActiveMember(actorId, organizationId);
        return driveRepository.findByOrganizationIdAndStatusOrderById(organizationId, status);
    }

    @Transactional
    public PlacementDrive updateDetails(
            Long actorId, Long organizationId, Long driveId,
            String name, String description, Instant opensAt, Instant closesAt) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        if (drive.getStatus() != PlacementDriveStatus.DRAFT
                && drive.getStatus() != PlacementDriveStatus.PUBLISHED) {
            throw conflict("DRIVE_DETAILS_LOCKED", "Drive details cannot be changed in this state");
        }
        validateName(name);
        validateRegistrationWindow(opensAt, closesAt);
        drive.setName(name.trim());
        drive.setDescription(trimToNull(description));
        drive.setRegistrationOpensAt(opensAt);
        drive.setRegistrationClosesAt(closesAt);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive publish(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        requireStatus(drive, PlacementDriveStatus.DRAFT, "Only a draft drive can be published");
        drive.setStatus(PlacementDriveStatus.PUBLISHED);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive openRegistration(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        requireStatus(drive, PlacementDriveStatus.PUBLISHED,
                "Registration can be opened only for a published drive");
        Instant now = Instant.now();
        if (drive.getRegistrationOpensAt() != null
                && now.isBefore(drive.getRegistrationOpensAt())) {
            throw conflict("REGISTRATION_NOT_OPEN", "The registration opening time has not arrived");
        }
        if (drive.getRegistrationClosesAt() != null
                && !now.isBefore(drive.getRegistrationClosesAt())) {
            throw conflict("REGISTRATION_WINDOW_CLOSED", "The registration window has closed");
        }
        drive.setStatus(PlacementDriveStatus.REGISTRATION_OPEN);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive closeRegistration(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        requireStatus(drive, PlacementDriveStatus.REGISTRATION_OPEN,
                "Only an open registration can be closed");
        drive.setStatus(PlacementDriveStatus.REGISTRATION_CLOSED);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive start(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        requireStatus(drive, PlacementDriveStatus.REGISTRATION_CLOSED,
                "Registration must be closed before starting a drive");
        drive.setStatus(PlacementDriveStatus.STARTED);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive complete(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        requireStatus(drive, PlacementDriveStatus.STARTED,
                "Only a started drive can be completed");
        drive.setStatus(PlacementDriveStatus.COMPLETED);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive cancel(Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        if (drive.getStatus() == PlacementDriveStatus.COMPLETED
                || drive.getStatus() == PlacementDriveStatus.CANCELLED) {
            throw conflict("INVALID_DRIVE_TRANSITION", "A completed or cancelled drive is terminal");
        }
        drive.setStatus(PlacementDriveStatus.CANCELLED);
        return driveRepository.save(drive);
    }

    @Transactional
    public PlacementDrive updateCapacity(
            Long actorId, Long organizationId, Long driveId, int newCapacity) {
        access.requireDriveManager(actorId, organizationId);
        validateCapacity(newCapacity);
        PlacementDrive drive = lockDrive(organizationId, driveId);
        long reserved = applicationRepository
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
                        organizationId, driveId);
        if (newCapacity < reserved) {
            throw conflict("CAPACITY_BELOW_RESERVATIONS",
                    "Capacity cannot be reduced below the number of reservations");
        }
        drive.setRegistrationCapacity(newCapacity);
        return driveRepository.save(drive);
    }

    private PlacementDrive lockDrive(Long organizationId, Long driveId) {
        return driveRepository.findByIdAndOrganizationIdForUpdate(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
    }

    private PlacementDrive requireDrive(Long organizationId, Long driveId) {
        return driveRepository.findByIdAndOrganizationId(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
    }

    private static void requireStatus(
            PlacementDrive drive, PlacementDriveStatus expected, String message) {
        if (drive.getStatus() != expected) {
            throw conflict("INVALID_DRIVE_TRANSITION", message);
        }
    }

    private static void validateCapacity(int capacity) {
        if (capacity <= 0) {
            throw conflict("INVALID_REGISTRATION_CAPACITY",
                    "Registration capacity must be positive");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw conflict("INVALID_DRIVE_NAME", "Drive name is required");
        }
    }

    private static void validateRegistrationWindow(Instant opensAt, Instant closesAt) {
        if (opensAt != null && closesAt != null && !closesAt.isAfter(opensAt)) {
            throw conflict("INVALID_REGISTRATION_WINDOW",
                    "Registration closing time must be later than opening time");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }
}
