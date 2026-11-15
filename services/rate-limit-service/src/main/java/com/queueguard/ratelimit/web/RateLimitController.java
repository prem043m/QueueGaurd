package com.queueguard.ratelimit.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.ratelimit.dto.RateLimitDtos.*;
import com.queueguard.ratelimit.service.RateLimitService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ratelimit")
public class RateLimitController {

    private final RateLimitService rateLimitService;

    public RateLimitController(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /** Called by the api-gateway for every request. */
    @PostMapping("/check")
    public ApiResponse<CheckDecision> check(@Valid @RequestBody CheckRequest req) {
        return ApiResponse.ok(rateLimitService.check(req));
    }

    /** Called by the monitor-service: feeds the adaptive controller. */
    @PostMapping("/load")
    public ApiResponse<AdaptiveView> load(@Valid @RequestBody LoadReport report) {
        return ApiResponse.ok(rateLimitService.reportLoad(report));
    }

    @GetMapping("/adaptive")
    public ApiResponse<AdaptiveView> adaptive() {
        return ApiResponse.ok(rateLimitService.adaptive());
    }

    @GetMapping("/bans")
    public ApiResponse<BanListResponse> bans() {
        return ApiResponse.ok(rateLimitService.activeBans());
    }

    @GetMapping("/ips/{ip}")
    public ApiResponse<IpStateView> ipState(@PathVariable String ip) {
        return ApiResponse.ok(rateLimitService.ipState(ip));
    }

    @PostMapping("/ban/{ip}")
    public ApiResponse<BanView> ban(@PathVariable String ip,
                                    @RequestParam(defaultValue = "10") long minutes,
                                    @RequestParam(required = false) String reason) {
        return ApiResponse.ok("Banned", rateLimitService.ban(ip, minutes, reason));
    }

    @DeleteMapping("/bans/{ip}")
    public ApiResponse<String> unban(@PathVariable String ip) {
        rateLimitService.unban(ip);
        return ApiResponse.ok("Unbanned", ip);
    }
}
