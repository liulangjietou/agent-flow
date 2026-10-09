package io.agentflow.signature;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import io.agentflow.common.DomainException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 使用真实 JDK 密钥签名验证原文、租户和请求绑定，合成回执不代表真实文件签署验收。
 *
 * @author owlzhangfq@gmail.com
 */
class SignatureReceiptVerifierTest {
    private final java.security.KeyPair pair = keyPair();
    private final SignatureProfile profile = profile(pair);
    private final SignatureOperation.Input input = input(profile);
    private final SignatureReceiptVerifier verifier = new SignatureReceiptVerifier(MAPPER);

    @ParameterizedTest @EnumSource(SignatureReceipt.Status.class)
    void allProviderObservationsCarryReverifiableRawEvidence(SignatureReceipt.Status status) {
        var receipt = receipt(input, status); byte[] body = body(profile, input, receipt); String signature = sign(pair, body);
        var verified = verifier.verify(input, profile, body, signature, NOW.plusSeconds(2));
        assertThat(verified.receipt()).isEqualTo(receipt);
        assertThat(Base64.getDecoder().decode(verified.evidence().payloadBase64())).containsExactly(body);
        Arrays.fill(body, (byte) 0);
        var restored = JSON.readStrict(JSON.write(verified.evidence()), SignatureReceiptVerifier.Evidence.class);
        assertThat(new SignatureReceiptVerifier(MAPPER).reverify(input, restored)).isEqualTo(verified);
        assertThat(restored.toString()).doesNotContain(signature, profile.receiptPublicKey(), "provider-company-1");
    }

    @Test void laterPollingKeepsTheSameProofIdentityAndCanVerifyAfterSendAuthorizationExpires() {
        var receipt = receipt(input, SignatureReceipt.Status.SIGNED); byte[] body = body(profile, input, receipt); String signature = sign(pair, body);
        var first = verifier.verify(input, profile, body, signature, NOW.plusSeconds(2));
        var later = verifier.verify(input, profile, body, signature, NOW.plusSeconds(7200));
        assertThat(first.evidence().digest()).isEqualTo(later.evidence().digest());
        assertThat(first.evidence().verifiedAt()).isNotEqualTo(later.evidence().verifiedAt());
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-key", "changed-byte", "missing-padding", "zero-signature", "oversized-signature"})
    void authenticationRejectsUntrustedSignaturesAndAnyByteMutation(String variant) {
        byte[] body = body(profile, input, receipt(input, SignatureReceipt.Status.PENDING));
        String signature = switch (variant) {
            case "wrong-key" -> sign(keyPair(), body);
            case "missing-padding" -> sign(pair, body).replace("=", "");
            case "zero-signature" -> Base64.getEncoder().encodeToString(new byte[64]);
            case "oversized-signature" -> "A".repeat(1000);
            default -> sign(pair, body);
        };
        if (variant.equals("changed-byte")) body[0] = ' ';
        assertUnverified(body, signature);
    }

    @ParameterizedTest @ValueSource(strings = {"tenant", "profile-key", "profile-version", "profile-digest", "target", "operation", "request", "protocol", "duplicate", "trailing", "unknown", "fraction", "coercion", "enum-number", "null-receipt", "missing", "future", "utf8"})
    void evenAuthenticProviderBytesMustSatisfyStrictSchemaAndOriginalBinding(String variant) {
        String text = new String(body(profile, input, receipt(input, SignatureReceipt.Status.PENDING)), StandardCharsets.UTF_8);
        String changed = switch (variant) {
            case "tenant" -> text.replace("tenant-a", "tenant-b");
            case "profile-key" -> text.replace("company-seal", "other-seal");
            case "profile-version" -> text.replace("\"profileVersion\":2", "\"profileVersion\":3");
            case "profile-digest" -> text.replace(profile.digest(), "0".repeat(64));
            case "target" -> text.replace(input.targetDigest(), "0".repeat(64));
            case "operation" -> text.replace(input.request().id().toString(), java.util.UUID.randomUUID().toString());
            case "request" -> text.replace(input.request().digest(), "0".repeat(64));
            case "protocol" -> text.replace(SignatureReceiptVerifier.PROTOCOL, "another-protocol");
            case "duplicate" -> text.replace("\"status\":\"PENDING\"", "\"status\":\"PENDING\",\"status\":\"PENDING\"");
            case "trailing" -> text + " {}";
            case "unknown" -> text.replace("\"revision\":1", "\"revision\":1,\"downloadUrl\":\"https://untrusted.invalid/\"");
            case "fraction" -> text.replace("\"revision\":1", "\"revision\":1.2");
            case "coercion" -> text.replace("\"revision\":1", "\"revision\":\"1\"");
            case "enum-number" -> text.replace("\"status\":\"PENDING\"", "\"status\":1");
            case "null-receipt" -> JSON.write(new SignatureReceiptVerifier.Envelope(SignatureReceiptVerifier.PROTOCOL, profile.tenantId(), profile.key(), profile.version(), profile.digest(), input.targetDigest(), null));
            case "missing" -> text.replace("\"profileVersion\":2,", "");
            case "future" -> text.replace(NOW.plusSeconds(1).toString(), NOW.plusSeconds(99).toString());
            case "utf8" -> text + " ";
            default -> throw new AssertionError(variant);
        };
        assertThat(changed).as(variant).isNotEqualTo(text);
        byte[] bytes = changed.getBytes(StandardCharsets.UTF_8);
        if (variant.equals("utf8")) bytes[bytes.length - 1] = (byte) 0xff;
        assertUnverified(bytes, sign(pair, bytes));
    }

    @Test void maximumRawBodyIsAcceptedButOverflowIsRejectedBeforeParsing() {
        byte[] raw = body(profile, input, receipt(input, SignatureReceipt.Status.PENDING));
        byte[] maximum = Arrays.copyOf(raw, SignatureReceiptVerifier.MAX_RECEIPT_BYTES); Arrays.fill(maximum, raw.length, maximum.length, (byte) ' ');
        assertThat(verifier.verify(input, profile, maximum, sign(pair, maximum), NOW.plusSeconds(2)).receipt().status()).isEqualTo(SignatureReceipt.Status.PENDING);
        byte[] overflow = Arrays.copyOf(maximum, maximum.length + 1); overflow[overflow.length - 1] = ' ';
        assertUnverified(overflow, sign(pair, overflow));
    }

    @Test void attackerCannotReplaceThePersistedPublicKeyAndResignTheEnvelope() {
        var attackerPair = keyPair(); var replacement = profile(attackerPair);
        var bytes = body(replacement, input, receipt(input, SignatureReceipt.Status.PENDING));
        var forged = new SignatureReceiptVerifier.Evidence(replacement, input.targetDigest(), Base64.getEncoder().encodeToString(bytes), sign(attackerPair, bytes), NOW.plusSeconds(2));
        assertThatThrownBy(() -> verifier.reverify(input, forged)).isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).code()).isEqualTo("SIGNATURE_RECEIPT_UNVERIFIED");
    }

    private void assertUnverified(byte[] body, String signature) {
        assertThatThrownBy(() -> verifier.verify(input, profile, body, signature, NOW.plusSeconds(2))).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code()).isEqualTo("SIGNATURE_RECEIPT_UNVERIFIED");
    }
}
