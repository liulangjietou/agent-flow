package io.agentflow.auth;

import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 本地用户口令安全哈希工具：使用 PBKDF2WithHmacSHA256、随机盐与恒定时间比对，禁止明文存储口令。
 * @author owlzhangfq@gmail.com
 */
public final class LocalPasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT_PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int MIN_PASSWORD_LENGTH = 10;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalPasswordHasher() { }

    /** 校验口令强度（10~128 字符，至少包含字母与数字）并生成加盐哈希串。 */
    public static String hash(String rawPassword) {
        validateStrength(rawPassword);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return FORMAT_PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    /** 恒定时间校验口令是否与存储哈希匹配。 */
    public static boolean verify(String rawPassword, String encodedHash) {
        if (StringUtils.isEmpty(rawPassword) || StringUtils.isBlank(encodedHash)) {
            return false;
        }
        String[] parts = encodedHash.split("\\$");
        if (parts.length != 4 || !FORMAT_PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** 统一入口校验新口令复杂度。 */
    public static void validateStrength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "Password must be between 10 and 128 characters");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "Password must contain both letters and digits");
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception failure) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", failure);
        } finally {
            spec.clearPassword();
        }
    }
}
