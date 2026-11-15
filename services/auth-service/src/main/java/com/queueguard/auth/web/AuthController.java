package com.queueguard.auth.web;

import com.queueguard.auth.dto.AuthDtos.AuthResponse;
import com.queueguard.auth.dto.AuthDtos.LoginRequest;
import com.queueguard.auth.dto.AuthDtos.RegisterRequest;
import com.queueguard.auth.dto.AuthDtos.UserResponse;
import com.queueguard.auth.service.AuthService;
import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.exception.UnauthorizedException;
import com.queueguard.common.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwt;

    public AuthController(AuthService authService, JwtUtil jwt) {
        this.authService = authService;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok("Registered", authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok("Logged in", authService.login(req));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        return ApiResponse.ok(authService.me(subjectOf(auth)));
    }

    private String subjectOf(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new UnauthorizedException("Missing bearer token");
        }
        try {
            return jwt.validate(auth.substring(7)).subject();
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid token: " + e.getMessage());
        }
    }
}
