package com.queueguard.common.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing using PBKDF2-HMAC-SHA256 (OWASP-acceptable, JDK-only).
 * Stored format: pbkdf2-sha256$&lt;iterations&gt;$&lt;saltB64&gt;$&lt;hashB64&gt;
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder enc = Base64.getEncoder();
        return "pbkdf2-sha256$" + ITERATIONS + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 failure", e);
        }
    }
}
