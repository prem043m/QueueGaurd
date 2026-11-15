package com.queueguard.monitor.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.monitor.entity.MonitorEntities.LoadSnapshot;
import com.queueguard.monitor.entity.MonitorEntities.MetricSample;
import com.queueguard.monitor.service.MonitorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @PostMapping("/metrics")
    public ApiResponse<MetricSample> record(@RequestBody MetricSample sample) {
        return ApiResponse.ok(monitorService.recordMetrics(sample));
    }

    @GetMapping("/health-overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(monitorService.healthOverview());
    }

    @GetMapping("/load")
    public ApiResponse<LoadSnapshot> load() {
        return ApiResponse.ok(monitorService.latestLoad().orElse(null));
    }

    @GetMapping("/timeline")
    public ApiResponse<List<LoadSnapshot>> timeline(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(monitorService.timeline(limit));
    }

    @GetMapping("/metrics")
    public ApiResponse<List<MetricSample>> metrics() {
        return ApiResponse.ok(monitorService.recentMetrics());
    }
}
