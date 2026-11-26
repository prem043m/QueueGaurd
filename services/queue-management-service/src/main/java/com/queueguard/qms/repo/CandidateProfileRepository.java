package com.queueguard.qms.repo;

import com.queueguard.qms.domain.CandidateProfile;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<CandidateProfile> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    boolean existsByUserIdAndOrganizationId(Long userId, Long organizationId);

    @Query(value = """
            select count(*) from candidate_profiles profile
            join memberships membership
              on membership.organization_id = profile.organization_id
             and membership.auth_user_id = profile.user_id
            where profile.organization_id = :organizationId
              and (:fromAt is null or profile.created_at >= :fromAt)
              and (:toAt is null or profile.created_at < :toAt)
            """, nativeQuery = true)
    long countForAnalytics(@Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);
}
