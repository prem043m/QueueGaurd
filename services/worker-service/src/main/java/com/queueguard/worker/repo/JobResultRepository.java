package com.queueguard.worker.repo;

import com.queueguard.worker.entity.JobResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobResultRepository extends JpaRepository<JobResult, Long> {
    long countByStatus(String status);
}
