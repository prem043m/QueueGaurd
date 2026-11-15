package com.queueguard.ratelimit.service;

import com.queueguard.common.exception.NotFoundException;
import com.queueguard.ratelimit.dto.RateLimitDtos.*;
import com.queueguard.ratelimit.entity.AdaptiveState;
import com.queueguard.ratelimit.entity.IpBan;
import com.queueguard.ratelimit.limiter.TokenBucket;
import com.queueguard.ratelimit.repo.AdaptiveStateRepository;
import com.queueguard.ratelimit.repo.IpBanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Adaptive, IP-aware rate limiting with a strike/ban escalation ladder.
 *
 * Limits are token buckets whose refill rate and burst are scaled by a live
 * "multiplier": when the monitor reports high load or an attack is under way,
 * the multiplier shrinks (protecting the system); when the system idles, it
 * grows up to 1.5x (better UX for legitimate users).
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final double EWMA_ALPHA = 0.3;

    private final TokenBucket bucket;
    private final IpBanRepository bans;
    private final AdaptiveStateRepository adaptiveRepo;

    private final double baseLimitPerSec;
    private final double baseBurst;
    private final double userLimitPerSec;
    private final double userBurst;
    private final int strikeBanThreshold;
    private final long banBaseMinutes;
    private final long banMaxMinutes;
    private final long strikeDecaySec;

    public RateLimitService(TokenBucket bucket,
                            IpBanRepository bans,
                            AdaptiveStateRepository adaptiveRepo,
                            @Value("${queueguard.ratelimit.base-limit-per-sec:10}") double baseLimitPerSec,
                            @Value("${queueguard.ratelimit.burst:20}") double baseBurst,
                            @Value("${queueguard.ratelimit.user-limit-per-sec:30}") double userLimitPerSec,
                            @Value("${queueguard.ratelimit.user-burst:60}") double userBurst,
                            @Value("${queueguard.ratelimit.strike-ban-threshold:5}") int strikeBanThreshold,
                            @Value("${queueguard.ratelimit.ban-base-minutes:1}") long banBaseMinutes,
                            @Value("${queueguard.ratelimit.ban-max-minutes:60}") long banMaxMinutes,
                            @Value("${queueguard.ratelimit.strike-decay-sec:60}") long strikeDecaySec) {
        this.bucket = bucket;
        this.bans = bans;
        this.adaptiveRepo = adaptiveRepo;
        this.baseLimitPerSec = baseLimitPerSec;
        this.baseBurst = baseBurst;
        this.userLimitPerSec = userLimitPerSec;
        this.userBurst = userBurst;
        this.strikeBanThreshold = strikeBanThreshold;
        this.banBaseMinutes = banBaseMinutes;
        this.banMaxMinutes = banMaxMinutes;
        this.strikeDecaySec = strikeDecaySec;
    }

    // ------------------------------------------------------------------ enforcement

    @Transactional
    public CheckDecision check(CheckRequest req) {
        Instant now = Instant.now();
        double multiplier = currentMultiplier();
        long cost = req.cost() == null || req.cost() < 1 ? 1 : req.cost();

        IpBan record = bans.findByIp(req.ip()).orElse(null);

        // 1) Hard ban wins over everything.
        if (record != null && record.isBannedAt(now)) {
            long retryMs = Math.max(1000, record.getBannedUntil().toEpochMilli() - now.toEpochMilli());
            return new CheckDecision(false, req.ip(), req.userId(),
                    effLimit(multiplier), effBurst(multiplier), 0, retryMs, multiplier, true,
                    "IP temporarily banned until " + record.getBannedUntil());
        }

        // 2) Per-IP token bucket (scaled by the adaptive multiplier).
        double ipLimit = effLimit(multiplier);
        double ipBurst = effBurst(multiplier);
        TokenBucket.Result ipResult = bucket.consume("qg:rl:ip:" + req.ip(), ipBurst, ipLimit, cost);
        if (!ipResult.allowed()) {
            String message = registerStrike(req.ip(), record, now);
            return new CheckDecision(false, req.ip(), req.userId(),
                    ipLimit, ipBurst, 0, Math.max(50, ipResult.retryAfterMs()), multiplier,
                    message != null, message == null ? "Per-IP rate limit exceeded" : message);
        }

        // 3) Per-user bucket (higher limit; identified by JWT subject).
        double remaining = ipResult.remainingTokens();
        if (req.userId() != null && !req.userId().isBlank()) {
            double uLimit = userLimitPerSec * multiplier;
            double uBurst = userBurst * multiplier;
            TokenBucket.Result userResult = bucket.consume("qg:rl:user:" + req.userId(), uBurst, uLimit, cost);
            if (!userResult.allowed()) {
                return new CheckDecision(false, req.ip(), req.userId(),
                        uLimit, uBurst, 0, Math.max(50, userResult.retryAfterMs()), multiplier, false,
                        "Per-user rate limit exceeded");
            }
            remaining = Math.min(remaining, userResult.remainingTokens());
        }

        // Slow down strike decay so well-behaved IPs recover quickly.
        decayStrikes(record, now);

        return new CheckDecision(true, req.ip(), req.userId(),
                ipLimit, ipBurst, remaining, 0, multiplier, false, "OK");
    }

    /**
     * Strike ladder: each denial is a strike (decaying over time). Once an IP
     * crosses the threshold it is banned with exponential backoff:
     * 1, 2, 4, 8 ... minutes, capped.
     */
    private String registerStrike(String ip, IpBan record, Instant now) {
        IpBan ban = record == null ? new IpBan() : record;
        if (ban.getIp() == null) {
            ban.setIp(ip);
        }
        // Strikes that are stale decay before the new one is applied.
        if (ban.getLastStrikeAt() != null && ban.getLastStrikeAt().isBefore(now.minusSeconds(strikeDecaySec))) {
            ban.setStrikes(Math.max(0, ban.getStrikes() - 1));
        }
        ban.setStrikes(ban.getStrikes() + 1);
        ban.setLastStrikeAt(now);
        ban.setUpdatedAt(now);

        if (ban.getStrikes() >= strikeBanThreshold) {
            long over = ban.getStrikes() - strikeBanThreshold;
            long minutes = Math.min(banMaxMinutes, banBaseMinutes * (1L << Math.min(over, 10)));
            ban.setBannedUntil(now.plusSeconds(minutes * 60));
            ban.setReason("Exceeded per-IP rate limit " + ban.getStrikes() + " times");
            bans.save(ban);
            log.warn("IP {} banned for {} minutes after {} strikes", ip, minutes, ban.getStrikes());
            return "Banned for " + minutes + " minutes after " + ban.getStrikes() + " violations";
        }
        bans.save(ban);
        return null;
    }

    private void decayStrikes(IpBan record, Instant now) {
        if (record != null && record.getStrikes() > 0
                && record.getLastStrikeAt() != null
                && record.getLastStrikeAt().isBefore(now.minusSeconds(strikeDecaySec))) {
            record.setStrikes(Math.max(0, record.getStrikes() - 1));
            record.setUpdatedAt(now);
            bans.save(record);
        }
    }

    // ------------------------------------------------------------------ adaptivity

    /**
     * Called by the monitor service with a live load score in [0,1].
     * target multiplier = clamp(1.5 - 1.3 * load, 0.2, 1.5), smoothed with EWMA.
     */
    @Transactional
    public AdaptiveView reportLoad(LoadReport report) {
        double load = report.loadScore() == null ? 0.0 : Math.max(0.0, Math.min(1.0, report.loadScore()));
        double target = Math.max(0.2, Math.min(1.5, 1.5 - 1.3 * load));
        AdaptiveState state = loadState();
        double updated = EWMA_ALPHA * target + (1 - EWMA_ALPHA) * state.getMultiplier();
        state.setMultiplier(updated);
        state.setTargetMultiplier(target);
        state.setLoadScore(load);
        state.setUpdatedAt(Instant.now());
        adaptiveRepo.save(state);
        log.info("Adaptive limits: load={} -> target={} multiplier={} (queueDepth={}, inFlight={}, errorRate={})",
                String.format("%.2f", load), String.format("%.2f", target), String.format("%.2f", updated),
                report.queueDepth(), report.inFlight(), report.errorRate());
        return toAdaptiveView(state);
    }

    @Transactional(readOnly = true)
    public AdaptiveView adaptive() {
        return toAdaptiveView(loadState());
    }

    private AdaptiveState loadState() {
        return adaptiveRepo.findById(1L).orElseGet(() -> {
            AdaptiveState s = new AdaptiveState();
            return adaptiveRepo.save(s);
        });
    }

    private double currentMultiplier() {
        return adaptiveRepo.findById(1L).map(AdaptiveState::getMultiplier).orElse(1.0);
    }

    private double effLimit(double multiplier) {
        return Math.max(0.5, baseLimitPerSec * multiplier);
    }

    private double effBurst(double multiplier) {
        return Math.max(1.0, Math.round(baseBurst * multiplier));
    }

    // ------------------------------------------------------------------ admin

    @Transactional
    public BanView ban(String ip, long minutes, String reason) {
        IpBan record = bans.findByIp(ip).orElseGet(IpBan::new);
        record.setIp(ip);
        record.setBannedUntil(Instant.now().plusSeconds(Math.max(1, minutes) * 60));
        record.setReason(reason == null ? "manual ban" : reason);
        record.setStrikes(Math.max(record.getStrikes(), strikeBanThreshold));
        record.setUpdatedAt(Instant.now());
        bans.save(record);
        log.warn("IP {} manually banned for {} minutes: {}", ip, minutes, record.getReason());
        return toBanView(record);
    }

    @Transactional
    public void unban(String ip) {
        IpBan record = bans.findByIp(ip).orElseThrow(() -> new NotFoundException("No ban record for " + ip));
        record.setBannedUntil(null);
        record.setStrikes(0);
        record.setReason(null);
        record.setUpdatedAt(Instant.now());
        bans.save(record);
    }

    @Transactional(readOnly = true)
    public BanListResponse activeBans() {
        List<BanView> list = bans.findByBannedUntilAfter(Instant.now()).stream().map(this::toBanView).toList();
        return new BanListResponse(list.size(), list);
    }

    @Transactional(readOnly = true)
    public IpStateView ipState(String ip) {
        IpBan record = bans.findByIp(ip).orElse(null);
        double multiplier = currentMultiplier();
        TokenBucket.Result probe = bucket.consume("qg:rl:ip:" + ip, effBurst(multiplier), effLimit(multiplier), 0);
        return new IpStateView(ip,
                record == null ? 0 : record.getStrikes(),
                record != null && record.isBannedAt(Instant.now()),
                record == null || record.getBannedUntil() == null ? null : record.getBannedUntil().toString(),
                probe.remainingTokens(),
                String.format("%.2f", multiplier));
    }

    private BanView toBanView(IpBan b) {
        return new BanView(b.getIp(), b.getStrikes(),
                b.getBannedUntil() == null ? null : b.getBannedUntil().toString(),
                b.getReason(), b.getUpdatedAt().toString());
    }

    private AdaptiveView toAdaptiveView(AdaptiveState s) {
        return new AdaptiveView(s.getMultiplier(), s.getTargetMultiplier(), s.getLoadScore(),
                baseLimitPerSec, baseLimitPerSec * s.getMultiplier(), s.getUpdatedAt().toString());
    }
}
