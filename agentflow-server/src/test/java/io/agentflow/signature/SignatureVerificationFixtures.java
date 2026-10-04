package io.agentflow.signature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 仅在测试服务方生成私钥和合成回执，应用配置及证据永远只保存公钥。
 * @author owlzhangfq@gmail.com
 */
final class SignatureVerificationFixtures {
    static final Instant NOW = SignaturePersistenceFixtures.NOW;
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final JsonUtil JSON = new JsonUtil(MAPPER);
    private SignatureVerificationFixtures() { }

    static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance(SignatureProfile.ALGORITHM).generateKeyPair(); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    static SignatureProfile profile(KeyPair pair) {
        return new SignatureProfile("tenant-a", "company-seal", 2, "企业合同签署", List.of("alice"),
                List.of(new SignatureRequest.Signer("company", "provider-company-1")), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }
    static SignatureOperation.Input input(SignatureProfile profile) {
        UUID document = UUID.randomUUID();
        var request = new SignatureRequest(UUID.randomUUID(), profile.tenantId(), new SignatureRequest.Source(UUID.randomUUID(), 2, 7, "contract", 3),
                new SignatureRequest.Authorization("alice", profile.key(), profile.version(), profile.digest(), "合同签署授权", NOW, NOW.plusSeconds(3600)),
                List.of(new SignatureRequest.Document(document, document, "contract", "合同.pdf", 1001, "a".repeat(64))), profile.signers());
        return new SignatureOperation.Input(request, "d".repeat(64));
    }
    static SignatureReceipt receipt(SignatureOperation.Input input, SignatureReceipt.Status status) {
        return SignaturePersistenceFixtures.receipt(SignatureOperation.queue(input, NOW), status, 1, NOW.plusSeconds(1));
    }
    static byte[] body(SignatureProfile profile, SignatureOperation.Input input, SignatureReceipt receipt) {
        return JSON.write(new SignatureReceiptVerifier.Envelope(SignatureReceiptVerifier.PROTOCOL, profile.tenantId(), profile.key(), profile.version(), profile.digest(),
                input.targetDigest(), receipt)).getBytes(StandardCharsets.UTF_8);
    }
    static String sign(KeyPair pair, byte[] body) {
        try {
            var signature = Signature.getInstance(SignatureProfile.ALGORITHM); signature.initSign(pair.getPrivate());
            signature.update((SignatureReceiptVerifier.PROTOCOL + "\n").getBytes(StandardCharsets.UTF_8)); signature.update(body);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
