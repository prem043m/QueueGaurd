package com.queueguard.qms.repo;

import com.queueguard.qms.domain.CandidateProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<CandidateProfile> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    boolean existsByUserIdAndOrganizationId(Long userId, Long organizationId);
}
