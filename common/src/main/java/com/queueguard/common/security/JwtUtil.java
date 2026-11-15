package com.queueguard.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Lightweight, dependency-free JWT (HS256) implementation.
 * Used by auth-service to issue tokens and by api-gateway to verify them.
 */
public final class JwtUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] secret;
    private final long ttlSeconds;

    public JwtUtil(String secret, long ttlSeconds) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("JWT secret must be at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** Issues a signed token for the given subject with optional extra claims. */
    public String issue(String subject, Map<String, Object> extraClaims) {
        long now = Instant.now().getEpochSecond();
        ObjectNode header = MAPPER.createObjectNode();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sub", subject);
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);
        if (extraClaims != null) {
            extraClaims.forEach((k, v) -> payload.set(k, MAPPER.valueToTree(v)));
        }
        String head = b64(write(header));
        String body = b64(write(payload));
        String signature = hs256(head + "." + body);
        return head + "." + body + "." + signature;
    }

    /** Validates signature and expiry, returning the claims. Throws IllegalArgumentException if invalid. */
    public JwtClaims validate(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Empty token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed token");
        }
        String expected = hs256(parts[0] + "." + parts[1]);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = parts[2].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new IllegalArgumentException("Bad signature");
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable payload");
        }
        long exp = payload.path("exp").asLong(0);
        if (exp < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("Token expired");
        }
        return new JwtClaims(
                payload.path("sub").asText(null),
                payload.path("email").asText(null),
                exp,
                payload
        );
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private String hs256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return b64(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static byte[] write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON failure", e);
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Immutable view of validated token claims. */
    public record JwtClaims(String subject, String email, long expiresAtEpochSec, JsonNode raw) {
    }
}
