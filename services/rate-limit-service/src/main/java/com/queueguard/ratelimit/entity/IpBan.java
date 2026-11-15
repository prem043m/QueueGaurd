package com.queueguard.ratelimit.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Persistent record of abusive IPs: strikes, exponential bans. */
@Entity
@Table(name = "ip_bans", indexes = @Index(name = "idx_ban_ip", columnList = "ip", unique = true))
public class IpBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String ip;

    @Column(nullable = false)
    private int strikes = 0;

    private Instant bannedUntil;

    private Instant lastStrikeAt;

    @Column(length = 300)
    private String reason;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public int getStrikes() { return strikes; }
    public void setStrikes(int strikes) { this.strikes = strikes; }
    public Instant getBannedUntil() { return bannedUntil; }
    public void setBannedUntil(Instant bannedUntil) { this.bannedUntil = bannedUntil; }
    public Instant getLastStrikeAt() { return lastStrikeAt; }
    public void setLastStrikeAt(Instant lastStrikeAt) { this.lastStrikeAt = lastStrikeAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isBannedAt(Instant now) {
        return bannedUntil != null && bannedUntil.isAfter(now);
    }
}
