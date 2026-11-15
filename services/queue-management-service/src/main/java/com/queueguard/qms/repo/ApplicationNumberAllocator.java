package com.queueguard.qms.repo;

public interface ApplicationNumberAllocator {
    long allocateNextApplicationNumber(Long organizationId, Long placementDriveId);
}
