package com.queueguard.queue.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.queue.dto.QueueDtos.*;
import com.queueguard.queue.service.QueueService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    // ------------------------------------------------------------- queue management

    @PostMapping("/queues")
    public ApiResponse<QueueView> createQueue(@Valid @RequestBody CreateQueueRequest req) {
        return ApiResponse.ok("Queue created", queueService.createQueue(req));
    }

    @GetMapping("/queues")
    public ApiResponse<List<QueueView>> listQueues() {
        return ApiResponse.ok(queueService.listQueues());
    }

    @GetMapping("/queues/{name}/stats")
    public ApiResponse<StatsResponse> stats(@PathVariable String name) {
        return ApiResponse.ok(queueService.stats(name));
    }

    // ------------------------------------------------------------- producers

    @PostMapping("/queues/{name}/messages")
    public ApiResponse<EnqueueResponse> enqueue(@PathVariable String name,
                                                @Valid @RequestBody EnqueueRequest req,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader) {
        EnqueueRequest merged = new EnqueueRequest(req.payload(), req.priority(),
                req.idempotencyKey() != null ? req.idempotencyKey() : idemHeader, req.delayMs());
        return ApiResponse.ok("Enqueued", queueService.enqueue(name, merged));
    }

    // ------------------------------------------------------------- consumers

    @PostMapping("/queues/{name}/claim")
    public ApiResponse<ClaimResponse> claim(@PathVariable String name, @RequestBody(required = false) ClaimRequest req) {
        return ApiResponse.ok(queueService.claim(name, req == null ? new ClaimRequest(1, "anonymous-worker") : req));
    }

    @PostMapping("/messages/{id}/ack")
    public ApiResponse<MessageView> ack(@PathVariable String id, @RequestBody(required = false) WorkerActionRequest req) {
        return ApiResponse.ok("Acknowledged", queueService.ack(id, req == null ? new WorkerActionRequest(null, null) : req));
    }

    @PostMapping("/messages/{id}/nack")
    public ApiResponse<MessageView> nack(@PathVariable String id, @RequestBody(required = false) WorkerActionRequest req) {
        return ApiResponse.ok("Negatively acknowledged", queueService.nack(id, req == null ? new WorkerActionRequest(null, null) : req));
    }

    // ------------------------------------------------------------- dead letters

    @GetMapping("/queues/{name}/dead")
    public ApiResponse<List<MessageView>> dead(@PathVariable String name,
                                               @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(queueService.deadLetters(name, limit));
    }

    @PostMapping("/queues/{name}/dead/{id}/requeue")
    public ApiResponse<MessageView> requeue(@PathVariable String name, @PathVariable String id) {
        return ApiResponse.ok("Requeued", queueService.requeueDead(name, id));
    }
}
