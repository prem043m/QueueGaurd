package com.queueguard.qms.repo;
import com.queueguard.qms.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface MembershipRepository extends JpaRepository<Membership, Long> {
 Optional<Membership> findByOrganizationIdAndAuthUserId(Long organizationId, Long authUserId);
 List<Membership> findByAuthUserIdOrderByCreatedAtDesc(Long authUserId);
 List<Membership> findByOrganizationIdOrderByCreatedAtAsc(Long organizationId);
}
