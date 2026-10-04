package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次明确授权对应一个原操作号、已批准轮次及不可替换的文件和签署身份集合。
 * @author owlzhangfq@gmail.com
 */
public record SignatureRequest(UUID id, String tenantId, Source source, Authorization authorization,
                               List<Document> documents, List<Signer> signers) {
    public static final int MAX_DOCUMENTS = 10;
    public static final int MAX_SIGNERS = 10;
    public static final long MAX_DOCUMENT_BYTES = 16L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    public static final Duration MAX_AUTHORIZATION_LIFETIME = Duration.ofHours(24);
    static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    private static final Pattern FIELD_PATH = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}(\\.[A-Za-z][A-Za-z0-9_]{0,63})?");

    /** 文件与签署方只在这里冻结，后续恢复不能重新解析姓名、当前字段或新配置。 */
    public SignatureRequest {
        if (id == null || !literal(tenantId, 64) || source == null || authorization == null
                || documents == null || documents.isEmpty() || documents.size() > MAX_DOCUMENTS || documents.stream().anyMatch(java.util.Objects::isNull)
                || signers == null || signers.isEmpty() || signers.size() > MAX_SIGNERS || signers.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        documents = documents.stream().sorted(Comparator.comparing(value -> value.attachmentId().toString())).toList();
        signers = signers.stream().sorted(Comparator.comparing(Signer::key)).toList();
        if (documents.stream().map(Document::attachmentId).distinct().count() != documents.size()
                || documents.stream().mapToLong(Document::size).sum() > MAX_TOTAL_BYTES
                || signers.stream().map(Signer::key).distinct().count() != signers.size()
                || signers.stream().map(Signer::providerSubject).distinct().count() != signers.size()) throw invalid();
    }

    /** 长度前缀的 UTF-8 摘要同时固定来源、授权、文件指纹及真实签署身份，避免拼接歧义。 */
    public String digest() {
        var digest = sha256();
        add(digest, "agentflow-signature-request-1", id.toString(), tenantId, source.applicationId().toString(),
                Integer.toString(source.roundNo()), Long.toString(source.applicationVersion()), source.processKey(), Long.toString(source.definitionVersion()),
                authorization.actor(), authorization.profileKey(), Long.toString(authorization.profileVersion()), authorization.profileDigest(),
                authorization.purpose(), authorization.authorizedAt().toString(), authorization.validUntil().toString(), Integer.toString(documents.size()));
        for (var document : documents) add(digest, document.attachmentId().toString(), document.contentId().toString(), document.fieldPath(), document.filename(),
                Long.toString(document.size()), document.sha256());
        add(digest, Integer.toString(signers.size()));
        for (var signer : signers) add(digest, signer.key(), signer.providerSubject());
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 默认诊断文本不输出文件名、签署身份、授权意见或服务账户。 */
    @Override public String toString() { return "SignatureRequest[id=" + id + ", applicationId=" + source.applicationId()
            + ", roundNo=" + source.roundNo() + ", documents=" + documents.size() + ", signers=" + signers.size() + "]"; }

    /**
     * 业务入口先验证实际批准状态；这个来源只固定核对过的版本，不自行批准申请。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID applicationId, int roundNo, long applicationVersion, String processKey, long definitionVersion) {
        public Source {
            if (applicationId == null || roundNo < 1 || applicationVersion < 1 || !literal(processKey, 128) || definitionVersion < 1) throw invalid();
        }
    }

    /**
     * 授权来自当前具名操作和可信签署配置；批准申请不自动授予印章或签名使用权限。
     * @author owlzhangfq@gmail.com
     */
    public record Authorization(String actor, String profileKey, long profileVersion, String profileDigest,
                                String purpose, Instant authorizedAt, Instant validUntil) {
        public Authorization {
            if (!literal(actor, 128) || !key(profileKey) || profileVersion < 1 || !digestValue(profileDigest) || !literal(purpose, 1000)
                    || authorizedAt == null || validUntil == null || !validUntil.isAfter(authorizedAt)
                    || Duration.between(authorizedAt, validUntil).compareTo(MAX_AUTHORIZATION_LIFETIME) > 0) throw invalid();
        }
        @Override public String toString() { return "SignatureAuthorization[profile=" + profileKey + ", version=" + profileVersion + "]"; }
    }

    /**
     * 附件引用和物理原件分别固定；子流程复制引用仍指向原来的不可覆盖内容。
     * @author owlzhangfq@gmail.com
     */
    public record Document(UUID attachmentId, UUID contentId, String fieldPath, String filename, long size, String sha256) {
        public Document {
            if (attachmentId == null || contentId == null || fieldPath == null || !FIELD_PATH.matcher(fieldPath).matches()
                    || !literal(filename, 255) || filename.contains("/") || filename.contains("\\")
                    || size < 1 || size > MAX_DOCUMENT_BYTES || !digestValue(sha256)) throw invalid();
        }
        @Override public String toString() { return "SignatureDocument[id=" + attachmentId + ", size=" + size + "]"; }
    }

    /**
     * 实际签署身份由可信配置绑定，不能从显示姓名、审批角色或请求正文临时推断。
     * @author owlzhangfq@gmail.com
     */
    public record Signer(String key, String providerSubject) {
        public Signer { if (!SignatureRequest.key(key) || !literal(providerSubject, 128)) throw invalid(); }
        @Override public String toString() { return "SignatureSigner[key=" + key + "]"; }
    }

    static boolean key(String value) { return value != null && KEY.matcher(value).matches(); }
    static boolean digestValue(String value) { return value != null && DIGEST.matcher(value).matches(); }
    static boolean literal(String value, int maximum) {
        return !StringUtils.isBlank(value) && value.length() <= maximum && value.equals(value.strip())
                && value.codePoints().noneMatch(cp -> Character.isISOControl(cp) || cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    static void add(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SIGNATURE_REQUEST", "Signature request requires a fixed source, explicit authorization, documents and configured signers"); }
}
