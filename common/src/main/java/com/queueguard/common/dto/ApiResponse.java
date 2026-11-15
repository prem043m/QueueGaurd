package com.queueguard.common.dto;

import java.time.Instant;

/** Uniform JSON envelope returned by every QueueGuard service. */
public record ApiResponse<T>(boolean success, String message, T data, long timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data, Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null, Instant.now().toEpochMilli());
    }
}
