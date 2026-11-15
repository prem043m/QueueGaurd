package com.queueguard.auth.service;

import com.queueguard.auth.dto.AuthDtos.AuthResponse;
import com.queueguard.auth.dto.AuthDtos.LoginRequest;
import com.queueguard.auth.dto.AuthDtos.RegisterRequest;
import com.queueguard.auth.dto.AuthDtos.UserResponse;
import com.queueguard.auth.entity.UserAccount;
import com.queueguard.auth.repo.UserRepository;
import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.common.exception.UnauthorizedException;
import com.queueguard.common.security.JwtUtil;
import com.queueguard.common.security.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuthService {

    private final UserRepository users;
    private final JwtUtil jwt;

    public AuthService(UserRepository users, JwtUtil jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().toLowerCase().trim();
        if (users.existsByEmail(email)) {
            throw new BusinessException(409, "EMAIL_TAKEN", "Email already registered");
        }
        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setPasswordHash(PasswordHasher.hash(req.password()));
        user.setDisplayName(req.displayName().trim());
        users.save(user);
        return tokenFor(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        UserAccount user = users.findByEmail(req.email().toLowerCase().trim())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        if (!PasswordHasher.verify(req.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return tokenFor(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me(String email) {
        UserAccount user = users.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new NotFoundException("User not found"));
        return toResponse(user);
    }

    private AuthResponse tokenFor(UserAccount user) {
        String token = jwt.issue(
                user.getEmail(),
                Map.of("uid", String.valueOf(user.getId()), "name", user.getDisplayName())
        );
        return new AuthResponse(token, jwt.ttlSeconds(), toResponse(user));
    }

    private UserResponse toResponse(UserAccount u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getCreatedAt().toString());
    }
}
