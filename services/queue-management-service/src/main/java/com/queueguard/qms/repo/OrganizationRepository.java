package com.queueguard.qms.repo;
import com.queueguard.qms.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrganizationRepository extends JpaRepository<Organization, Long> { boolean existsByNameIgnoreCase(String name); }
