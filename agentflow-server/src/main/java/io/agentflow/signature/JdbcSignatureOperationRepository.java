package io.agentflow.signature;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 签署原授权、轮次原件、领取和文件保存共用事务，服务端重启不能生成新的外发身份。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSignatureOperationRepository {
    private static final int DUE_LIMIT = 10;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 沿用应用事务与统一 JSON 编解码边界。 */
    public JdbcSignatureOperationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 调用方先授权，再锁申请核对批准版本；固定源文件与首条轨迹一并创建。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SignatureOperation operation) {
        if (operation.version() != 1 || operation.status() != SignatureOperation.Status.QUEUED) throw conflict();
        var input = operation.input(); var request = input.request(); var source = request.source();
        var application = jdbc.queryForList("""
                SELECT id FROM approval_application WHERE tenant_id=? AND id=? AND status='APPROVED'
                AND round_no=? AND version=? AND process_key=? AND definition_version=? FOR UPDATE
                """, request.tenantId(), source.applicationId().toString(), source.roundNo(), source.applicationVersion(), source.processKey(), source.definitionVersion());
        if (application.size() != 1 || jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_submission_round WHERE tenant_id=? AND application_id=? AND round_no=?
                AND definition_version=? AND status='APPROVED'
                """, Integer.class, request.tenantId(), source.applicationId().toString(), source.roundNo(), source.definitionVersion()) != 1) throw conflict();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM signature_operation WHERE tenant_id=? AND application_id=? AND round_no=? AND active_guard=1",
                Integer.class, request.tenantId(), source.applicationId().toString(), source.roundNo()) != 0) {
            throw new DomainException("SIGNATURE_OPERATION_ACTIVE", "The approved round already has an unfinished signature operation");
        }
        jdbc.update("""
                INSERT INTO signature_operation(tenant_id,id,application_id,round_no,request_digest,target_digest,input_json,state_json,
                version,status,attempts,authorized_at,updated_at,next_attempt_at,poll_at,active_guard,trace_id)
                VALUES(?,?,?,?,?,?,?,?,1,'QUEUED',0,?,?,?,?,1,?)
                """, request.tenantId(), request.id().toString(), source.applicationId().toString(), source.roundNo(), request.digest(), input.targetDigest(),
                json.write(input), json.write(operation), timestamp(request.authorization().authorizedAt()), timestamp(operation.updatedAt()),
                timestamp(operation.nextAttemptAt()), timestamp(operation.nextAttemptAt()), DiagnosticContext.capture().traceId());
        for (var document : request.documents()) insertSource(request, document);
        append(operation);
    }

    /** 原输入不改写，只有相邻领取版本可更新；已接受的签署结果不能被替换或退回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SignatureOperation operation) {
        var request = operation.input().request();
        var previous = lock(request.tenantId(), request.id()).orElseThrow(JdbcSignatureOperationRepository::conflict);
        if (previous.terminal() || operation.version() != Math.incrementExact(previous.version())
                || !operation.input().equals(previous.input()) || operation.updatedAt().isBefore(previous.updatedAt())) throw conflict();
        boolean reserved = signedReceipt(previous);
        if (reserved && (!signedReceipt(operation) || !previous.receipt().digest().equals(operation.receipt().digest())
                || !previous.artifacts().equals(operation.artifacts()))) throw conflict();
        if (operation.status() == SignatureOperation.Status.SIGNED && (!reserved || resultFiles(previous).stream().anyMatch(file -> !file.ready()))) throw conflict();
        Instant pollAt = operation.running() ? operation.leaseUntil() : operation.nextAttemptAt();
        int changed = jdbc.update("""
                UPDATE signature_operation SET version=?,status=?,attempts=?,state_json=?,updated_at=?,next_attempt_at=?,lease_until=?,poll_at=?,active_guard=?
                WHERE tenant_id=? AND id=? AND version=? AND request_digest=? AND target_digest=? AND active_guard=1
                """, operation.version(), operation.status().name(), operation.attempts(), json.write(operation), timestamp(operation.updatedAt()),
                timestamp(operation.nextAttemptAt()), timestamp(operation.leaseUntil()), timestamp(pollAt), operation.terminal() ? null : 1,
                request.tenantId(), request.id().toString(), previous.version(), request.digest(), operation.input().targetDigest());
        if (changed != 1) throw conflict();
        append(operation);
        if (!reserved && signedReceipt(operation)) {
            for (var file : operation.artifacts()) {
                jdbc.update("""
                        INSERT INTO signature_result_file(tenant_id,operation_id,document_id,content_id,byte_size,sha256,receipt_digest,created_version,status)
                        VALUES(?,?,?,?,?,?,?,?,'RESERVED')
                        """, request.tenantId(), request.id().toString(), file.documentId().toString(), file.contentId().toString(), file.size(), file.sha256(),
                        operation.receipt().digest(), operation.version());
            }
        }
    }

    /** 文件存储层已验证真实字节后逐份确认；迟到工作器不能凭过期领取记录文件成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markArtifactReady(SignatureOperation claim, SignatureOperation.StoredArtifact file, Instant now) {
        var request = claim.input().request();
        var current = lock(request.tenantId(), request.id()).orElseThrow(JdbcSignatureOperationRepository::conflict);
        if (!current.equals(claim) || claim.status() != SignatureOperation.Status.FETCHING_FILES
                || now.isBefore(claim.updatedAt()) || claim.expired(now) || !claim.artifacts().contains(file)) throw conflict();
        var stored = resultFiles(current).stream().filter(value -> value.artifact().documentId().equals(file.documentId())).findFirst().orElseThrow(JdbcSignatureOperationRepository::conflict);
        if (stored.ready()) return;
        if (jdbc.update("""
                UPDATE signature_result_file SET status='READY',saved_at=?,saved_version=? WHERE tenant_id=? AND operation_id=? AND document_id=?
                AND content_id=? AND byte_size=? AND sha256=? AND status='RESERVED'
                """, timestamp(now), claim.version(), request.tenantId(), request.id().toString(), file.documentId().toString(), file.contentId().toString(), file.size(), file.sha256()) != 1) throw conflict();
    }

    /** 租户限定读取并核对原始索引、冻结引用及结果指纹；调用方负责当前可见权限。 */
    public Optional<SignatureOperation> find(String tenant, UUID id) {
        return read("SELECT * FROM signature_operation WHERE tenant_id=? AND id=?", tenant, id.toString()).stream().findFirst();
    }

    /** 调用方沿用申请在前、操作在后的锁顺序，回调和工作器不能各自覆盖另一方。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SignatureOperation> lock(String tenant, UUID id) {
        return read("SELECT * FROM signature_operation WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id.toString()).stream().findFirst();
    }

    /** 按固定授权时间和标识翻页，入口须先核对轮次权限和游标归属。 */
    public List<SignatureOperation> forRound(String tenant, UUID applicationId, int roundNo, SignatureOperation after, int limit) {
        String sql = "SELECT * FROM signature_operation WHERE tenant_id=? AND application_id=? AND round_no=?";
        if (after == null) return read(sql + " ORDER BY authorized_at,id LIMIT ?", tenant, applicationId.toString(), roundNo, limit);
        var authorizedAt = timestamp(after.input().request().authorization().authorizedAt());
        return read(sql + " AND (authorized_at>? OR (authorized_at=? AND id>?)) ORDER BY authorized_at,id LIMIT ?", tenant,
                applicationId.toString(), roundNo, authorizedAt, authorizedAt, after.input().request().id().toString(), limit);
    }

    /** 后台只扫描到期身份；查询、发送和文件下载都必须另行取得新的持久领取。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,s.process_instance_id FROM signature_operation q
                LEFT JOIN approval_application a ON a.tenant_id=q.tenant_id AND a.id=q.application_id
                LEFT JOIN approval_submission_round s ON s.tenant_id=a.tenant_id AND s.application_id=a.id AND s.round_no=q.round_no
                WHERE q.poll_at<=? AND q.active_guard=1 ORDER BY q.poll_at,q.tenant_id,q.id LIMIT ?
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), timestamp(now), DUE_LIMIT);
    }

    /** 读取固定原操作的连续历史；旧状态的结果文件尚未分配时不能套用最新文件清单。 */
    public List<SignatureOperation> history(SignatureOperation current, long afterVersion, int limit) {
        var request = current.input().request();
        return jdbc.query("""
                SELECT version,state_json,occurred_at FROM signature_operation_revision
                WHERE tenant_id=? AND operation_id=? AND version>? AND version<=? ORDER BY version LIMIT ?
                """, (row, index) -> {
            var value = json.readStrict(row.getString("state_json"), SignatureOperation.class);
            if (!value.input().equals(current.input()) || value.version() != row.getLong("version")
                    || !indexed(value.updatedAt()).equals(instant(row.getTimestamp("occurred_at")))) throw corrupted();
            return value;
        }, request.tenantId(), request.id().toString(), afterVersion, current.version(), limit);
    }

    /** 读取已分配和已保存的结果，用于断点收集；元数据不能替代文件存储层的字节复核。 */
    public List<ResultFile> resultFiles(SignatureOperation operation) {
        var request = operation.input().request();
        var values = jdbc.query("""
                SELECT * FROM signature_result_file WHERE tenant_id=? AND operation_id=? AND created_version<=? ORDER BY document_id
                """, (row, index) -> {
            var file = new SignatureOperation.StoredArtifact(UUID.fromString(row.getString("document_id")), UUID.fromString(row.getString("content_id")), row.getLong("byte_size"), row.getString("sha256"));
            if (!signedReceipt(operation) || !operation.artifacts().contains(file) || !operation.receipt().digest().equals(row.getString("receipt_digest"))) throw corrupted();
            boolean ready = row.getString("status").equals("READY");
            var savedAt = instant(row.getTimestamp("saved_at"));
            var savedVersion = (Long) row.getObject("saved_version");
            if (ready != (savedAt != null) || ready != (savedVersion != null)
                    || savedAt != null && savedAt.isBefore(indexed(operation.receipt().recordedAt()))
                    || savedVersion != null && savedVersion <= row.getLong("created_version")) throw corrupted();
            return new ResultFile(file, ready, savedAt, savedVersion);
        }, request.tenantId(), request.id().toString(), operation.version());
        if (!values.stream().map(ResultFile::artifact).toList().equals(operation.artifacts())
                || operation.status() == SignatureOperation.Status.SIGNED && values.stream().anyMatch(value -> !value.ready())) throw corrupted();
        return values;
    }

    private void insertSource(SignatureRequest request, SignatureRequest.Document document) {
        var source = request.source();
        int inserted = jdbc.update("""
                INSERT INTO signature_source_document(tenant_id,operation_id,application_id,round_no,attachment_id,original_content_id,field_path,filename,byte_size,sha256,original_status)
                SELECT a.tenant_id,?,a.application_id,?,a.id,COALESCE(a.content_id,a.id),a.field_path,a.filename,a.byte_size,a.sha256,'READY'
                FROM approval_attachment a JOIN approval_attachment_round r ON r.tenant_id=a.tenant_id AND r.application_id=a.application_id AND r.attachment_id=a.id AND r.field_path=a.field_path
                WHERE a.tenant_id=? AND a.application_id=? AND r.round_no=? AND a.id=? AND COALESCE(a.content_id,a.id)=?
                AND a.field_path=? AND a.filename=? AND a.byte_size=? AND a.sha256=? AND a.status='READY'
                """, request.id().toString(), source.roundNo(), request.tenantId(), source.applicationId().toString(), source.roundNo(),
                document.attachmentId().toString(), document.contentId().toString(), document.fieldPath(), document.filename(), document.size(), document.sha256());
        if (inserted != 1) throw new DomainException("SIGNATURE_SOURCE_CHANGED", "A signature source document is not the fixed ready original in the approved round");
    }

    private List<SignatureOperation> read(String sql, Object... arguments) {
        var values = jdbc.query(sql, row(), arguments);
        for (var value : values) {
            var request = value.input().request();
            var documents = jdbc.query("""
                    SELECT attachment_id,original_content_id,field_path,filename,byte_size,sha256 FROM signature_source_document
                    WHERE tenant_id=? AND operation_id=?
                    """, (row, index) -> new SignatureRequest.Document(UUID.fromString(row.getString("attachment_id")), UUID.fromString(row.getString("original_content_id")),
                    row.getString("field_path"), row.getString("filename"), row.getLong("byte_size"), row.getString("sha256")), request.tenantId(), request.id().toString());
            if (documents.size() != request.documents().size() || !new HashSet<>(documents).equals(new HashSet<>(request.documents()))) throw corrupted();
            resultFiles(value);
        }
        return values;
    }

    private RowMapper<SignatureOperation> row() {
        return (row, index) -> {
            var value = json.readStrict(row.getString("state_json"), SignatureOperation.class);
            var input = value.input(); var request = input.request(); var source = request.source();
            Instant pollAt = value.running() ? value.leaseUntil() : value.nextAttemptAt();
            if (!input.equals(json.readStrict(row.getString("input_json"), SignatureOperation.Input.class))
                    || !request.tenantId().equals(row.getString("tenant_id")) || !request.id().toString().equals(row.getString("id"))
                    || !source.applicationId().toString().equals(row.getString("application_id")) || source.roundNo() != row.getInt("round_no")
                    || !request.digest().equals(row.getString("request_digest")) || !input.targetDigest().equals(row.getString("target_digest"))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                    || !indexed(request.authorization().authorizedAt()).equals(instant(row.getTimestamp("authorized_at")))
                    || !indexed(value.updatedAt()).equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(indexed(value.nextAttemptAt()), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(indexed(value.leaseUntil()), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(indexed(pollAt), instant(row.getTimestamp("poll_at")))
                    || !Objects.equals(value.terminal() ? null : 1, row.getObject("active_guard"))) throw corrupted();
            return value;
        };
    }

    private void append(SignatureOperation operation) {
        var request = operation.input().request();
        jdbc.update("INSERT INTO signature_operation_revision(tenant_id,operation_id,version,state_json,occurred_at) VALUES(?,?,?,?,?)",
                request.tenantId(), request.id().toString(), operation.version(), json.write(operation), timestamp(operation.updatedAt()));
    }

    // JSON 保留原始时间精度；数据库索引统一截到微秒，H2 与 PostgreSQL 不得各自舍入后改变摘要。
    private static Instant indexed(Instant value) { return value == null ? null : value.truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(indexed(value)); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static boolean signedReceipt(SignatureOperation operation) { return operation.receipt() != null && operation.receipt().status() == SignatureReceipt.Status.SIGNED; }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Signature source, original request, claim or saved files changed"); }
    private static IllegalStateException corrupted() { return new IllegalStateException("Persisted signature identity, source or result metadata is inconsistent"); }

    /**
     * 调度扫描仅暴露租户和原操作号，不携带原件或签署身份。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务事实不存在时保持空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }

    /**
     * 已保存状态来自当前领取下的存储确认；后续下载仍须核对实际文件字节。
     * @author owlzhangfq@gmail.com
     */
    public record ResultFile(SignatureOperation.StoredArtifact artifact, boolean ready, Instant savedAt, Long savedVersion) { }
}
