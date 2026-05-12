package com.warehouse.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted SHA-256 password hashing.
 *
 * The hash format is {@code base64(salt) + ":" + base64(sha256(salt || password))}.
 * SHA-256 is not as slow as bcrypt or Argon2, but for an academic
 * single-instance project it is more than enough; this avoids pulling
 * in any third-party crypto dependency.
 */
public final class PasswordHasher {

    private static final int SALT_BYTES = 16;
    private static final SecureRandom RNG = new SecureRandom();

    private PasswordHasher() { }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + sha256Base64(salt, password);
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || !stored.contains(":")) return false;
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        String expected = parts[1];
        String actual = sha256Base64(salt, password);
        return constantTimeEquals(expected, actual);
    }

    private static String sha256Base64(byte[] salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
