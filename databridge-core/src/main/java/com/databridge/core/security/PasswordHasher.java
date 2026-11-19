package com.databridge.core.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hashes in the form {@code pbkdf2$<iterations>$<salt>$<hash>}
 * (Base64). Verification is constant-time and honours the iterations stored with each hash, so
 * the work factor can be raised later without breaking existing passwords.
 */
public final class PasswordHasher {

    public static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public PasswordHasher() {
        this(ITERATIONS);
    }

    /** Fewer iterations, for tests only. */
    public PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = derive(password, salt, iterations);
        Base64.Encoder b = Base64.getEncoder();
        return "pbkdf2$" + iterations + "$" + b.encodeToString(salt) + "$" + b.encodeToString(h);
    }

    public boolean verify(char[] password, String stored) {
        if (stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        try {
            Base64.Decoder b = Base64.getDecoder();
            byte[] want = b.decode(p[3]);
            byte[] got = derive(password, b.decode(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(want, got);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A random password for new accounts and resets: 16 characters, no look-alike letters. */
    public static String temporaryPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        }
    }
}
