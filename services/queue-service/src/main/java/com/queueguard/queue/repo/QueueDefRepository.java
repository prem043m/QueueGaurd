package com.queueguard.queue.repo;

import com.queueguard.queue.entity.QueueDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QueueDefRepository extends JpaRepository<QueueDef, Long> {
    Optional<QueueDef> findByName(String name);
    boolean existsByName(String name);
}
