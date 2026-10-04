package io.agentflow.signature;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 验证服务方对完整原始回执的签名并保留原文，不将业务摘要冒充真实性证明。
 * @author owlzhangfq@gmail.com
 */
@Component
public class SignatureReceiptVerifier {
    public static final String PROTOCOL = "agentflow-signature-receipt-1";
    public static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final int SIGNATURE_BYTES = 64;
    private static final byte[] SIGNING_PREFIX = (PROTOCOL + "\n").getBytes(StandardCharsets.UTF_8);
    private final JsonUtil json;

    /** 严格解析只用于签署协议，不能改变应用其他 JSON 入口的兼容行为。 */
    public SignatureReceiptVerifier(ObjectMapper mapper) {
        json = new JsonUtil(mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    }

    /** 先验原始字节的签名，再解析内容并绑定原租户、版本、服务目标、请求及文件指纹。 */
    public Verified verify(SignatureOperation.Input input, SignatureProfile profile, byte[] body, String signature, Instant now) {
        if (profile == null || body == null || body.length < 1 || body.length > MAX_RECEIPT_BYTES || now == null || !profile.matches(input.request())) throw invalid();
        body = body.clone();
        byte[] signatureBytes = decode(signature, SIGNATURE_BYTES);
        if (signatureBytes.length != SIGNATURE_BYTES) throw invalid();
        try {
            var verifier = Signature.getInstance(SignatureProfile.ALGORITHM);
            verifier.initVerify(profile.publicKey()); verifier.update(SIGNING_PREFIX); verifier.update(body);
            if (!verifier.verify(signatureBytes)) throw invalid();
            var envelope = json.readStrict(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString(), Envelope.class);
            if (envelope == null || !PROTOCOL.equals(envelope.protocol()) || !profile.tenantId().equals(envelope.tenantId())
                    || !profile.key().equals(envelope.profileKey()) || profile.version() != envelope.profileVersion() || !profile.digest().equals(envelope.profileDigest())
                    || !input.targetDigest().equals(envelope.targetDigest()) || envelope.receipt() == null || !envelope.receipt().matches(input.request(), now)) throw invalid();
            return new Verified(envelope.receipt(), new Evidence(profile, input.targetDigest(), Base64.getEncoder().encodeToString(body), signature, now));
        } catch (GeneralSecurityException | CharacterCodingException | DomainException failure) { throw invalid(); }
    }

    /** 恢复证据按原授权中固定的公钥摘要重新验真，不依赖当前配置仍然保留旧密钥。 */
    public Verified reverify(SignatureOperation.Input input, Evidence evidence) {
        if (evidence == null || !input.targetDigest().equals(evidence.targetDigest())) throw invalid();
        return verify(input, evidence.profile(), decode(evidence.payloadBase64(), MAX_RECEIPT_BYTES), evidence.signatureBase64(), evidence.verifiedAt());
    }

    private static byte[] decode(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > 4 * ((maximum + 2) / 3)) throw invalid();
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > maximum || !Base64.getEncoder().encodeToString(decoded).equals(value)) throw invalid();
            return decoded;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /**
     * 所有字段均为签名正文的一部分，算法固定，不接受正文指定算法或下载地址。
     * @author owlzhangfq@gmail.com
     */
    public record Envelope(String protocol, String tenantId, String profileKey, long profileVersion, String profileDigest,
                           String targetDigest, SignatureReceipt receipt) { }

    /**
     * 保存完整部署公钥绑定、原始字节和签名；接收时间不改变同一份证据的幂等标识。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(SignatureProfile profile, String targetDigest, String payloadBase64, String signatureBase64, Instant verifiedAt) {
        /** 稳定标识区分不同原文和不同公钥绑定，但不为重复轮询制造新证据。 */
        public String digest() {
            var digest = SignatureRequest.sha256();
            SignatureRequest.add(digest, "agentflow-signature-evidence-1", profile.digest(), targetDigest, payloadBase64, signatureBase64);
            return HexFormat.of().formatHex(digest.digest());
        }
        @Override public String toString() { return "SignatureEvidence[redacted]"; }
    }

    /**
     * 仅表示服务方回执的真实性；文件签名、证书链和时间戳仍需实际服务及文件核验。
     * @author owlzhangfq@gmail.com
     */
    public record Verified(SignatureReceipt receipt, Evidence evidence) { }
    private static DomainException invalid() { return new DomainException("SIGNATURE_RECEIPT_UNVERIFIED", "Signature receipt authentication or original request binding failed"); }
}
