package com.queueguard.ratelimit.repo;

import com.queueguard.ratelimit.entity.AdaptiveState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdaptiveStateRepository extends JpaRepository<AdaptiveState, Long> {
}
