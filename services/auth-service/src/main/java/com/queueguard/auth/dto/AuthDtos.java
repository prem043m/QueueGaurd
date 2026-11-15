package com.queueguard.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(min = 2, max = 120) String displayName) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record UserResponse(Long id, String email, String displayName, String createdAt) {
    }

    public record AuthResponse(String token, long expiresInSeconds, UserResponse user) {
    }
}
