package io.agentflow.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP 动态口令生成器与校验器（HMAC-SHA1，6 位数字，30 秒步长，±1 步时钟容错）。
 * @author owlzhangfq@gmail.com
 */
public final class TotpAuthenticator {
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final long STEP_SECONDS = 30L;
    private static final int DIGITS = 6;
    private static final int MODULO = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpAuthenticator() { }

    /** 生成 160-bit 随机密钥并编码为标准无填充 Base32 字符串。 */
    public static String generateSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return encodeBase32(raw);
    }

    /** 构造标准 otpauth://totp URI，供认证器 App 扫码绑定。 */
    public static String provisioningUri(String issuer, String accountLabel, String base32Secret) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(accountLabel, StandardCharsets.UTF_8);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /** 计算指定时刻的 6 位 TOTP 验证码。 */
    public static String codeAt(String base32Secret, Instant instant) {
        long counter = Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
        return codeForCounter(decodeBase32(base32Secret), counter);
    }

    /** 在 ±1 时间步（前后 30 秒）窗口内恒定时间校验 6 位数字验证码。 */
    public static boolean verify(String base32Secret, String code, Instant now) {
        if (base32Secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        byte[] key;
        try {
            key = decodeBase32(base32Secret);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        long currentStep = Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
        byte[] provided = code.getBytes(StandardCharsets.US_ASCII);
        for (long delta = -1L; delta <= 1L; delta++) {
            String expected = codeForCounter(key, currentStep + delta);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), provided)) {
                return true;
            }
        }
        return false;
    }

    private static String codeForCounter(byte[] key, long counter) {
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash = hmacSha1(key, data);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % MODULO;
        return String.format(Locale.ROOT, "%06d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception failure) {
            throw new IllegalStateException("HmacSHA1 unavailable", failure);
        }
    }

    static String encodeBase32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                out.append(BASE32_ALPHABET.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            out.append(BASE32_ALPHABET.charAt(index));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String encoded) {
        String clean = encoded.trim().replace("=", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) throw new IllegalArgumentException("Empty Base32 secret");
        byte[] out = new byte[(clean.length() * 5) / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int pos = 0;
        for (int i = 0; i < clean.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (val < 0) throw new IllegalArgumentException("Invalid Base32 character");
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[pos++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
