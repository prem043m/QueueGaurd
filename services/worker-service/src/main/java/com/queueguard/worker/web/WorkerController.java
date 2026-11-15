package com.queueguard.worker.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.worker.worker.WorkerEngine;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/workers")
public class WorkerController {

    private final WorkerEngine engine;

    public WorkerController(WorkerEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(engine.stats());
    }

    @PostMapping("/pause")
    public ApiResponse<String> pause() {
        engine.pause();
        return ApiResponse.ok("Worker paused", String.valueOf(engine.stats().get("workerId")));
    }

    @PostMapping("/resume")
    public ApiResponse<String> resume() {
        engine.resume();
        return ApiResponse.ok("Worker resumed", String.valueOf(engine.stats().get("workerId")));
    }
}
