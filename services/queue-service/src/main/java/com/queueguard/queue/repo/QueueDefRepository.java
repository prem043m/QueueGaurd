package com.queueguard.queue.repo;

import com.queueguard.queue.entity.QueueDef;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface QueueDefRepository extends JpaRepository<QueueDef, Long> {
    Optional<QueueDef> findByName(String name);
    boolean existsByName(String name);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO queue_defs (name, description, max_concurrency, max_attempts, created_at)
            VALUES ('default', 'Default local infrastructure work queue', 32, 5, CURRENT_TIMESTAMP)
            ON CONFLICT (name) DO NOTHING
            """, nativeQuery = true)
    int provisionDefaultIfAbsent();
}
