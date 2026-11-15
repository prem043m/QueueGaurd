package com.queueguard.qms.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApplicationNumberAllocatorImpl implements ApplicationNumberAllocator {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public long allocateNextApplicationNumber(Long organizationId, Long placementDriveId) {
        if (organizationId == null || placementDriveId == null) {
            throw new IllegalArgumentException("Organization and placement drive IDs are required");
        }

        Object result = entityManager.createNativeQuery("""
                INSERT INTO application_number_counters
                    (organization_id, placement_drive_id, last_number)
                VALUES (:organizationId, :placementDriveId, 1)
                ON CONFLICT (organization_id, placement_drive_id)
                DO UPDATE SET last_number =
                    application_number_counters.last_number + 1
                RETURNING last_number
                """)
                .setParameter("organizationId", organizationId)
                .setParameter("placementDriveId", placementDriveId)
                .getSingleResult();
        return ((Number) result).longValue();
    }
}
