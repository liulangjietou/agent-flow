package io.agentflow.signature;

import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 精确回调入口只用原回执签名认证；请求头用于定位原操作，不能替代已签名的租户与身份绑定。
 * @author owlzhangfq@gmail.com
 */
@Component
public class SignatureCallbackVerifier {
    public static final String PATH = "/api/v1/integrations/signatures/callbacks";
    public static final String TENANT_HEADER = "X-Agentflow-Signature-Tenant";
    public static final String OPERATION_HEADER = "X-Agentflow-Signature-Operation";
    private final JdbcSignatureOperationRepository operations;
    private final JdbcSignatureEvidenceRepository evidence;
    private final SignatureGatewayConfiguration configuration;
    private final SignatureReceiptVerifier verifier;

    /** 当前精确资料或既有已验真证据提供原公钥，正文不能自带替代密钥。 */
    public SignatureCallbackVerifier(JdbcSignatureOperationRepository operations, JdbcSignatureEvidenceRepository evidence,
            SignatureGatewayConfiguration configuration, SignatureReceiptVerifier verifier) {
        this.operations = operations; this.evidence = evidence; this.configuration = configuration; this.verifier = verifier;
    }

    /** 仅此 POST 免除用户会话和 CSRF，其他方法、尾斜杠和子路径仍由原认证链处理。 */
    public static boolean matches(HttpServletRequest request) { return "POST".equals(request.getMethod()) && PATH.equals(request.getRequestURI()); }

    /** 有界原文先验签再解析，重复头、查询串、压缩体及非 UTF-8 JSON 不进入业务状态机。 */
    public Callback verify(HttpServletRequest request, Instant now) throws IOException {
        if (!matches(request) || request.getQueryString() != null) throw invalid();
        String tenant = header(request, TENANT_HEADER), operation = header(request, OPERATION_HEADER);
        String signature = header(request, HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER);
        if (!SignatureRequest.literal(tenant, 64) || operation == null || operation.length() != 36 || signature == null || signature.length() != 88) throw unauthenticated();
        UUID id;
        try { id = UUID.fromString(operation); if (!id.toString().equals(operation)) throw unauthenticated(); }
        catch (IllegalArgumentException invalid) { throw unauthenticated(); }
        try {
            var contentType = MediaType.parseMediaType(header(request, "Content-Type"));
            if (!"application".equals(contentType.getType()) || !"json".equals(contentType.getSubtype())
                    || contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset())
                    || request.getHeader("Content-Encoding") != null) throw invalid();
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        if (request.getContentLengthLong() > SignatureReceiptVerifier.MAX_RECEIPT_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(SignatureReceiptVerifier.MAX_RECEIPT_BYTES + 1);
        if (body.length > SignatureReceiptVerifier.MAX_RECEIPT_BYTES) throw tooLarge();
        var current = operations.find(tenant, id).orElseThrow(SignatureCallbackVerifier::unauthenticated);
        var authorization = current.input().request().authorization();
        var profile = configuration.find(tenant, authorization.profileKey(), authorization.profileVersion()).map(SignatureGatewayConfiguration.Declaration::profile)
                .filter(value -> value.matches(current.input().request())).orElseGet(() -> current.receipt() == null ? null : evidence.forReceipt(current).evidence().profile());
        try { return new Callback(current.input(), verifier.verify(current.input(), profile, body, signature, now)); }
        catch (DomainException invalid) { throw unauthenticated(); }
    }

    private static String header(HttpServletRequest request, String name) {
        var values = request.getHeaders(name); if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement(); if (values.hasMoreElements() || value.length() > 256) throw unauthenticated(); return value;
    }
    private static DomainException unauthenticated() { return new DomainException("SIGNATURE_CALLBACK_UNAUTHENTICATED", "Signature callback authentication failed"); }
    private static DomainException invalid() { return new DomainException("INVALID_SIGNATURE_CALLBACK", "Signature callback request is invalid"); }
    private static DomainException tooLarge() { return new DomainException("SIGNATURE_CALLBACK_TOO_LARGE", "Signature callback body exceeds the byte limit"); }

    /**
     * 内部编排携带不可变原输入和完整证据，公开回调响应不返回该对象。
     * @author owlzhangfq@gmail.com
     */
    public record Callback(SignatureOperation.Input input, SignatureReceiptVerifier.Verified verified) {
        @Override public String toString() { return "SignatureCallback[redacted]"; }
    }
}
