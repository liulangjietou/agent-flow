package io.agentflow.signature;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.signature.mapper.SignatureOperationRepositoryMapper;

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
import java.util.function.Function;

/**
 * 签署原授权、轮次原件、领取和文件保存共用事务，服务端重启不能生成新的外发身份。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSignatureOperationRepository {
    private static final int DUE_LIMIT = 10;
    private final SignatureOperationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 沿用应用事务与统一 JSON 编解码边界。 */
    public JdbcSignatureOperationRepository(
            SignatureOperationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 调用方先授权，再锁申请核对批准版本；固定源文件与首条轨迹一并创建。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SignatureOperation operation) {
        if (operation.version() != 1 || operation.status() != SignatureOperation.Status.QUEUED)
            throw conflict();
        var input = operation.input();
        var request = input.request();
        var source = request.source();
        var application =
                SqlRows.maps(
                        sqlMapper.create(
                                request.tenantId(),
                                source.applicationId().toString(),
                                source.roundNo(),
                                source.applicationVersion(),
                                source.processKey(),
                                source.definitionVersion()));
        if (application.size() != 1
                || SqlRows.single(
                                sqlMapper.create2(
                                        request.tenantId(),
                                        source.applicationId().toString(),
                                        source.roundNo(),
                                        source.definitionVersion()))
                        != 1) throw conflict();
        if (SqlRows.single(
                        sqlMapper.create3(
                                request.tenantId(),
                                source.applicationId().toString(),
                                source.roundNo()))
                != 0) {
            throw new DomainException(
                    "SIGNATURE_OPERATION_ACTIVE",
                    "The approved round already has an unfinished signature operation");
        }
        sqlMapper.create4(
                request.tenantId(),
                request.id().toString(),
                source.applicationId().toString(),
                source.roundNo(),
                request.digest(),
                input.targetDigest(),
                json.write(input),
                json.write(operation),
                timestamp(request.authorization().authorizedAt()),
                timestamp(operation.updatedAt()),
                timestamp(operation.nextAttemptAt()),
                timestamp(operation.nextAttemptAt()),
                DiagnosticContext.capture().traceId());
        for (var document : request.documents()) insertSource(request, document);
        append(operation);
    }

    /** 原输入不改写，只有相邻领取版本可更新；已接受的签署结果不能被替换或退回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SignatureOperation operation) {
        var request = operation.input().request();
        var previous =
                lock(request.tenantId(), request.id())
                        .orElseThrow(JdbcSignatureOperationRepository::conflict);
        if (previous.terminal()
                || operation.version() != Math.incrementExact(previous.version())
                || !operation.input().equals(previous.input())
                || operation.updatedAt().isBefore(previous.updatedAt())) throw conflict();
        boolean reserved = signedReceipt(previous);
        if (reserved
                && (!signedReceipt(operation)
                        || !previous.receipt().digest().equals(operation.receipt().digest())
                        || !previous.artifacts().equals(operation.artifacts()))) throw conflict();
        if (operation.status() == SignatureOperation.Status.SIGNED
                && (!reserved || resultFiles(previous).stream().anyMatch(file -> !file.ready())))
            throw conflict();
        Instant pollAt = operation.running() ? operation.leaseUntil() : operation.nextAttemptAt();
        int changed =
                sqlMapper.update(
                        operation.version(),
                        operation.status().name(),
                        operation.attempts(),
                        json.write(operation),
                        timestamp(operation.updatedAt()),
                        timestamp(operation.nextAttemptAt()),
                        timestamp(operation.leaseUntil()),
                        timestamp(pollAt),
                        operation.terminal() ? null : 1,
                        request.tenantId(),
                        request.id().toString(),
                        previous.version(),
                        request.digest(),
                        operation.input().targetDigest());
        if (changed != 1) throw conflict();
        append(operation);
        if (!reserved && signedReceipt(operation)) {
            for (var file : operation.artifacts()) {
                sqlMapper.update2(
                        request.tenantId(),
                        request.id().toString(),
                        file.documentId().toString(),
                        file.contentId().toString(),
                        file.size(),
                        file.sha256(),
                        operation.receipt().digest(),
                        operation.version());
            }
        }
    }

    /** 文件存储层已验证真实字节后逐份确认；迟到工作器不能凭过期领取记录文件成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markArtifactReady(
            SignatureOperation claim, SignatureOperation.StoredArtifact file, Instant now) {
        var request = claim.input().request();
        var current =
                lock(request.tenantId(), request.id())
                        .orElseThrow(JdbcSignatureOperationRepository::conflict);
        if (!current.equals(claim)
                || claim.status() != SignatureOperation.Status.FETCHING_FILES
                || now.isBefore(claim.updatedAt())
                || claim.expired(now)
                || !claim.artifacts().contains(file)) throw conflict();
        var stored =
                resultFiles(current).stream()
                        .filter(value -> value.artifact().documentId().equals(file.documentId()))
                        .findFirst()
                        .orElseThrow(JdbcSignatureOperationRepository::conflict);
        if (stored.ready()) return;
        if (sqlMapper.markArtifactReady(
                        timestamp(now),
                        claim.version(),
                        request.tenantId(),
                        request.id().toString(),
                        file.documentId().toString(),
                        file.contentId().toString(),
                        file.size(),
                        file.sha256())
                != 1) throw conflict();
    }

    /** 租户限定读取并核对原始索引、冻结引用及结果指纹；调用方负责当前可见权限。 */
    public Optional<SignatureOperation> find(String tenant, UUID id) {
        return restoreOperations(sqlMapper.findRows(new Object[] {tenant, id.toString()})).stream()
                .findFirst();
    }

    /** 调用方沿用申请在前、操作在后的锁顺序，回调和工作器不能各自覆盖另一方。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SignatureOperation> lock(String tenant, UUID id) {
        return restoreOperations(sqlMapper.lockRows(new Object[] {tenant, id.toString()})).stream()
                .findFirst();
    }

    /** 按固定授权时间和标识翻页，入口须先核对轮次权限和游标归属。 */
    public List<SignatureOperation> forRound(
            String tenant, UUID applicationId, int roundNo, SignatureOperation after, int limit) {

        if (after == null)
            return restoreOperations(
                    sqlMapper.forRoundRows(
                            new Object[] {tenant, applicationId.toString(), roundNo, limit}));
        var authorizedAt = timestamp(after.input().request().authorization().authorizedAt());
        return restoreOperations(
                sqlMapper.forRoundRows2(
                        new Object[] {
                            tenant,
                            applicationId.toString(),
                            roundNo,
                            authorizedAt,
                            authorizedAt,
                            after.input().request().id().toString(),
                            limit
                        }));
    }

    /** 后台只扫描到期身份；查询、发送和文件下载都必须另行取得新的持久领取。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), DUE_LIMIT),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 读取固定原操作的连续历史；旧状态的结果文件尚未分配时不能套用最新文件清单。 */
    public List<SignatureOperation> history(
            SignatureOperation current, long afterVersion, int limit) {
        var request = current.input().request();
        return SqlRows.map(
                sqlMapper.history(
                        request.tenantId(),
                        request.id().toString(),
                        afterVersion,
                        current.version(),
                        limit),
                row -> {
                    var value =
                            json.readStrict(row.getString("state_json"), SignatureOperation.class);
                    if (!value.input().equals(current.input())
                            || value.version() != row.getLong("version")
                            || !indexed(value.updatedAt())
                                    .equals(instant(row.getTimestamp("occurred_at"))))
                        throw corrupted();
                    return value;
                });
    }

    /** 读取已分配和已保存的结果，用于断点收集；元数据不能替代文件存储层的字节复核。 */
    public List<ResultFile> resultFiles(SignatureOperation operation) {
        var request = operation.input().request();
        var values =
                SqlRows.map(
                        sqlMapper.resultFiles(
                                request.tenantId(), request.id().toString(), operation.version()),
                        row -> {
                            var file =
                                    new SignatureOperation.StoredArtifact(
                                            UUID.fromString(row.getString("document_id")),
                                            UUID.fromString(row.getString("content_id")),
                                            row.getLong("byte_size"),
                                            row.getString("sha256"));
                            if (!signedReceipt(operation)
                                    || !operation.artifacts().contains(file)
                                    || !operation
                                            .receipt()
                                            .digest()
                                            .equals(row.getString("receipt_digest")))
                                throw corrupted();
                            boolean ready = row.getString("status").equals("READY");
                            var savedAt = instant(row.getTimestamp("saved_at"));
                            var savedVersion = (Long) row.getObject("saved_version");
                            if (ready != (savedAt != null)
                                    || ready != (savedVersion != null)
                                    || savedAt != null
                                            && savedAt.isBefore(
                                                    indexed(operation.receipt().recordedAt()))
                                    || savedVersion != null
                                            && savedVersion <= row.getLong("created_version"))
                                throw corrupted();
                            return new ResultFile(file, ready, savedAt, savedVersion);
                        });
        if (!values.stream().map(ResultFile::artifact).toList().equals(operation.artifacts())
                || operation.status() == SignatureOperation.Status.SIGNED
                        && values.stream().anyMatch(value -> !value.ready())) throw corrupted();
        return values;
    }

    private void insertSource(SignatureRequest request, SignatureRequest.Document document) {
        var source = request.source();
        int inserted =
                sqlMapper.insertSource(
                        request.id().toString(),
                        source.roundNo(),
                        request.tenantId(),
                        source.applicationId().toString(),
                        source.roundNo(),
                        document.attachmentId().toString(),
                        document.contentId().toString(),
                        document.fieldPath(),
                        document.filename(),
                        document.size(),
                        document.sha256());
        if (inserted != 1)
            throw new DomainException(
                    "SIGNATURE_SOURCE_CHANGED",
                    "A signature source document is not the fixed ready original in the approved"
                        + " round");
    }

    private List<SignatureOperation> restoreOperations(List<SqlRow> rows) {
        var values = SqlRows.map(rows, row());
        for (var value : values) {
            var request = value.input().request();
            var documents =
                    SqlRows.map(
                            sqlMapper.read(request.tenantId(), request.id().toString()),
                            row ->
                                    new SignatureRequest.Document(
                                            UUID.fromString(row.getString("attachment_id")),
                                            UUID.fromString(row.getString("original_content_id")),
                                            row.getString("field_path"),
                                            row.getString("filename"),
                                            row.getLong("byte_size"),
                                            row.getString("sha256")));
            if (documents.size() != request.documents().size()
                    || !new HashSet<>(documents).equals(new HashSet<>(request.documents())))
                throw corrupted();
            resultFiles(value);
        }
        return values;
    }

    private Function<SqlRow, SignatureOperation> row() {
        return row -> {
            var value = json.readStrict(row.getString("state_json"), SignatureOperation.class);
            var input = value.input();
            var request = input.request();
            var source = request.source();
            Instant pollAt = value.running() ? value.leaseUntil() : value.nextAttemptAt();
            if (!input.equals(
                            json.readStrict(
                                    row.getString("input_json"), SignatureOperation.Input.class))
                    || !request.tenantId().equals(row.getString("tenant_id"))
                    || !request.id().toString().equals(row.getString("id"))
                    || !source.applicationId().toString().equals(row.getString("application_id"))
                    || source.roundNo() != row.getInt("round_no")
                    || !request.digest().equals(row.getString("request_digest"))
                    || !input.targetDigest().equals(row.getString("target_digest"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || !indexed(request.authorization().authorizedAt())
                            .equals(instant(row.getTimestamp("authorized_at")))
                    || !indexed(value.updatedAt()).equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            indexed(value.nextAttemptAt()),
                            instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(
                            indexed(value.leaseUntil()), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(indexed(pollAt), instant(row.getTimestamp("poll_at")))
                    || !Objects.equals(value.terminal() ? null : 1, row.getObject("active_guard")))
                throw corrupted();
            return value;
        };
    }

    private void append(SignatureOperation operation) {
        var request = operation.input().request();
        sqlMapper.append(
                request.tenantId(),
                request.id().toString(),
                operation.version(),
                json.write(operation),
                timestamp(operation.updatedAt()));
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
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务事实不存在时保持空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }

    /**
     * 已保存状态来自当前领取下的存储确认；后续下载仍须核对实际文件字节。
     *
     * @author owlzhangfq@gmail.com
     */
    public record ResultFile(
            SignatureOperation.StoredArtifact artifact,
            boolean ready,
            Instant savedAt,
            Long savedVersion) {}
}
