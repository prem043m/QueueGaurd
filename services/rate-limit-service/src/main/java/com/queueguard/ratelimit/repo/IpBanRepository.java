package com.queueguard.ratelimit.repo;

import com.queueguard.ratelimit.entity.IpBan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IpBanRepository extends JpaRepository<IpBan, Long> {
    Optional<IpBan> findByIp(String ip);
    List<IpBan> findByBannedUntilAfter(Instant now);
}
