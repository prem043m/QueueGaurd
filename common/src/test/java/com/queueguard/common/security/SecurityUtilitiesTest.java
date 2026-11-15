package com.queueguard.common.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityUtilitiesTest {

    @Test
    void passwordHashVerifiesOnlyTheOriginalPassword() {
        String stored = PasswordHasher.hash("CorrectHorseBatteryStaple!42");

        assertTrue(stored.startsWith("pbkdf2-sha256$"));
        assertTrue(PasswordHasher.verify("CorrectHorseBatteryStaple!42", stored));
        assertFalse(PasswordHasher.verify("wrong-password", stored));
        assertFalse(PasswordHasher.verify("CorrectHorseBatteryStaple!42", "not-a-password-hash"));
    }

    @Test
    void jwtRoundTripPreservesClaimsAndRejectsTampering() {
        JwtUtil jwt = new JwtUtil("test-secret-at-least-sixteen-chars", 60);
        String token = jwt.issue("student@example.test", Map.of("uid", "17", "role", "STUDENT"));

        JwtUtil.JwtClaims claims = jwt.validate(token);
        assertEquals("student@example.test", claims.subject());
        assertEquals("17", claims.raw().path("uid").asText());
        assertEquals("STUDENT", claims.raw().path("role").asText());

        String tampered = token.substring(0, token.length() - 1) + "x";
        assertThrows(IllegalArgumentException.class, () -> jwt.validate(tampered));
    }

    @Test
    void jwtRejectsAnExpiredToken() throws InterruptedException {
        JwtUtil jwt = new JwtUtil("test-secret-at-least-sixteen-chars", 0);
        String token = jwt.issue("expired@example.test", null);
        Thread.sleep(1100);

        assertThrows(IllegalArgumentException.class, () -> jwt.validate(token));
    }
}
