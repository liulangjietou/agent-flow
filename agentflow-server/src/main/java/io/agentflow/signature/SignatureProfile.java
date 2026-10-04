package io.agentflow.signature;

import io.agentflow.common.DomainException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 部署者声明的不可变签署授权与回执公钥绑定；该快照不包含访问令牌或私钥。
 * @author owlzhangfq@gmail.com
 */
public record SignatureProfile(String tenantId, String key, long version, String name, List<String> actors,
                               List<SignatureRequest.Signer> signers, String receiptPublicKey) {
    public static final String ALGORITHM = "Ed25519";
    private static final int MAX_ACTORS = 100;
    private static final int ENCODED_KEY_BYTES = 44;

    /** 精确主体、全部签署方及公钥同时参与版本摘要，不能通过显示姓名推导签署权。 */
    public SignatureProfile {
        if (!SignatureRequest.literal(tenantId, 64) || !SignatureRequest.key(key) || version < 1 || !SignatureRequest.literal(name, 128)
                || actors == null || actors.isEmpty() || actors.size() > MAX_ACTORS || actors.stream().anyMatch(actor -> !SignatureRequest.literal(actor, 128))
                || signers == null || signers.isEmpty() || signers.size() > SignatureRequest.MAX_SIGNERS || signers.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        actors = actors.stream().sorted().toList();
        signers = signers.stream().sorted(Comparator.comparing(SignatureRequest.Signer::key)).toList();
        if (actors.stream().distinct().count() != actors.size() || signers.stream().map(SignatureRequest.Signer::key).distinct().count() != signers.size()
                || signers.stream().map(SignatureRequest.Signer::providerSubject).distinct().count() != signers.size()) throw invalid();
        decodeKey(receiptPublicKey);
    }

    /** 摘要固定授权和验真身份；公钥轮换、授权人或签署身份变化都必须使用新版本。 */
    public String digest() {
        var digest = SignatureRequest.sha256();
        SignatureRequest.add(digest, "agentflow-signature-profile-1", tenantId, key, Long.toString(version), name, ALGORITHM, receiptPublicKey, Integer.toString(actors.size()));
        for (var actor : actors) SignatureRequest.add(digest, actor);
        SignatureRequest.add(digest, Integer.toString(signers.size()));
        for (var signer : signers) SignatureRequest.add(digest, signer.key(), signer.providerSubject());
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 重查旧回执仍按原快照核验；当前能否首次发送由入口另行检查启用状态和文件权限。 */
    public boolean matches(SignatureRequest request) {
        var authorization = request.authorization();
        return tenantId.equals(request.tenantId()) && key.equals(authorization.profileKey()) && version == authorization.profileVersion()
                && digest().equals(authorization.profileDigest()) && actors.contains(authorization.actor()) && signers.equals(request.signers());
    }

    PublicKey publicKey() { return decodeKey(receiptPublicKey); }

    private static PublicKey decodeKey(String encoded) {
        if (encoded == null || encoded.length() != 4 * ((ENCODED_KEY_BYTES + 2) / 3)) throw invalid();
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != ENCODED_KEY_BYTES || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw invalid();
            var key = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(bytes));
            if (!(key instanceof EdECPublicKey ed) || !ALGORITHM.equals(ed.getParams().getName()) || !Arrays.equals(bytes, key.getEncoded())) throw invalid();
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw invalid(); }
    }

    @Override public String toString() { return "SignatureProfile[key=" + key + ", version=" + version + "]"; }
    private static DomainException invalid() { return new DomainException("INVALID_SIGNATURE_PROFILE", "Signature profile requires explicit actors, signers and a canonical Ed25519 public key"); }
}
