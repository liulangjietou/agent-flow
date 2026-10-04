package io.agentflow.signature;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * 回执原文与已接受的操作修订共用事务；读取历史证据时重新验签并核对当时的状态。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSignatureEvidenceRepository {
    private static final int MAX_EVIDENCE_CHARS = 160 * 1024;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSignatureOperationRepository operations;
    private final SignatureReceiptVerifier verifier;

    /** 验真只依赖固定输入和保留公钥；配置撤下旧版本后，已有证据仍能独立重新核验。 */
    public JdbcSignatureEvidenceRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSignatureOperationRepository operations, SignatureReceiptVerifier verifier) {
        this.jdbc = jdbc; this.json = json; this.operations = operations; this.verifier = verifier;
    }

    /** 先在同一事务更新业务状态，再保存相应原文；重复接收保留首次验真时间和修订归属。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SignatureReceiptVerifier.Verified append(SignatureOperation accepted, SignatureReceiptVerifier.Evidence evidence) {
        var request = accepted.input().request();
        var current = operations.lock(request.tenantId(), request.id()).orElseThrow(JdbcSignatureEvidenceRepository::conflict);
        var verified = verifier.reverify(accepted.input(), evidence);
        if (!current.equals(accepted) || !accepted(accepted, verified.receipt())) throw conflict();
        var previous = find(request.tenantId(), request.id(), evidence.digest());
        if (previous.isPresent()) return previous.get();
        String serialized = json.write(evidence);
        if (serialized.length() > MAX_EVIDENCE_CHARS) throw conflict();
        jdbc.update("""
                INSERT INTO signature_receipt_evidence(tenant_id,operation_id,evidence_digest,receipt_digest,provider_revision,status,accepted_version,verified_at,evidence_json)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, request.tenantId(), request.id().toString(), evidence.digest(), verified.receipt().digest(), verified.receipt().revision(), verified.receipt().status().name(),
                accepted.version(), timestamp(evidence.verifiedAt()), serialized);
        return verified;
    }

    /** 仅返回指定租户操作的证据；调用方仍须核对申请和字段可见权限，不能直接对外暴露原文。 */
    public Optional<SignatureReceiptVerifier.Verified> find(String tenant, UUID operationId, String evidenceDigest) {
        var operation = operations.find(tenant, operationId);
        if (operation.isEmpty()) return Optional.empty();
        return jdbc.query("""
                SELECT e.*,r.state_json AS accepted_state FROM signature_receipt_evidence e
                JOIN signature_operation_revision r ON r.tenant_id=e.tenant_id AND r.operation_id=e.operation_id AND r.version=e.accepted_version
                WHERE e.tenant_id=? AND e.operation_id=? AND e.evidence_digest=?
                """, (row, index) -> {
            try {
                String serialized = row.getString("evidence_json");
                if (serialized.length() > MAX_EVIDENCE_CHARS) throw corrupt();
                var evidence = json.readStrict(serialized, SignatureReceiptVerifier.Evidence.class);
                var verified = verifier.reverify(operation.get().input(), evidence);
                var history = json.readStrict(row.getString("accepted_state"), SignatureOperation.class);
                if (!evidence.digest().equals(row.getString("evidence_digest")) || !verified.receipt().digest().equals(row.getString("receipt_digest"))
                        || !verified.receipt().status().name().equals(row.getString("status")) || verified.receipt().revision() != row.getLong("provider_revision")
                        || !timestamp(evidence.verifiedAt()).toInstant().equals(row.getTimestamp("verified_at").toInstant())
                        || history == null || history.version() != row.getLong("accepted_version") || !history.input().equals(operation.get().input()) || !accepted(history, verified.receipt())) throw corrupt();
                return verified;
            } catch (DomainException | IllegalArgumentException failure) { throw corrupt(); }
        }, tenant, operationId.toString(), evidenceDigest).stream().findFirst();
    }

    /** 文件恢复只取当前已接受回执的原始证据，不用配置或状态 JSON 补造签名。 */
    public SignatureReceiptVerifier.Verified forReceipt(SignatureOperation operation) {
        if (operation.receipt() == null) throw conflict();
        var request = operation.input().request();
        String digest = jdbc.query("""
                SELECT evidence_digest FROM signature_receipt_evidence WHERE tenant_id=? AND operation_id=?
                AND receipt_digest=? AND accepted_version<=? ORDER BY accepted_version,evidence_digest LIMIT 1
                """, (row, index) -> row.getString(1), request.tenantId(), request.id().toString(), operation.receipt().digest(), operation.version())
                .stream().findFirst().orElseThrow(JdbcSignatureEvidenceRepository::corrupt);
        var verified = find(request.tenantId(), request.id(), digest).orElseThrow(JdbcSignatureEvidenceRepository::corrupt);
        if (!verified.receipt().equals(operation.receipt())) throw corrupt();
        return verified;
    }

    private static boolean accepted(SignatureOperation operation, SignatureReceipt receipt) {
        if (operation.attempts() < 1) return false;
        if (receipt.status() == SignatureReceipt.Status.NOT_FOUND) return operation.status() == SignatureOperation.Status.UNKNOWN && operation.failure() == SignatureOperation.Failure.NOT_FOUND;
        return operation.receipt() != null && operation.receipt().digest().equals(receipt.digest());
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
    private static DomainException conflict() { return new DomainException("SIGNATURE_EVIDENCE_CONFLICT", "Signature evidence must match the accepted operation revision"); }
    private static DomainException corrupt() { return new DomainException("SIGNATURE_EVIDENCE_CORRUPT", "Stored signature evidence failed verification"); }
}
