package io.agentflow.finance;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.mapper.VoucherOperationRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 凭证执行记录与每版恢复事实同事务保存，禁止改写原命令、目标和业务绑定。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherOperationRepository {
    private final VoucherOperationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 仓储只处理持久事实，不在事务内调用 ERP。 */
    public JdbcVoucherOperationRepository(
            VoucherOperationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 业务行与申请已由应用服务锁定，同轮同类型唯一约束防止另造编号重复入账。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherOperation value) {
        if (value.version() != 1
                || value.status() != VoucherOperation.Status.QUEUED
                || value.attempts() != 0) throw conflict();
        var command = value.input().command();
        var binding = command.binding();
        sqlMapper.create(
                command.tenantId(),
                command.id().toString(),
                businessType(command).name(),
                binding.businessId().toString(),
                binding.applicationId().toString(),
                binding.roundNo(),
                command.kind().name(),
                binding.applicationVersion(),
                binding.businessVersion(),
                json.write(value.input()),
                command.digest(),
                json.write(value),
                timestamp(value.createdAt()),
                timestamp(value.updatedAt()),
                timestamp(value.nextAttemptAt()),
                DiagnosticContext.capture().traceId());
        append(value);
    }

    /** 领取版本和不可变输入共同约束更新，迟到执行者不能覆盖新租约结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(VoucherOperation value) {
        var command = value.input().command();
        int changed =
                sqlMapper.update(
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        value.highestRevision(),
                        json.write(value),
                        timestamp(value.updatedAt()),
                        timestamp(value.nextAttemptAt()),
                        timestamp(value.leaseUntil()),
                        value.reversalId() == null ? null : value.reversalId().toString(),
                        command.tenantId(),
                        command.id().toString(),
                        value.version() - 1,
                        json.write(value.input()),
                        command.digest());
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 按租户读取，并检查关系列、摘要和领域快照一致。 */
    public Optional<VoucherOperation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 裁决关联不可变修订，历史身份也必须匹配租户和原操作。 */
    public Optional<VoucherOperation> revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(row.getString("state_json"), VoucherOperation.class);
                            requireRevision(value, tenant, id, version);
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 扫描保留的修订以防后续失败覆盖历史过账，最初被接受的凭证身份保持不变。 */
    public DisputeEvidence disputeEvidence(String tenant, UUID id) {
        return restoreDisputeEvidence(
                tenant, id, sqlMapper.disputeEvidenceRows(new Object[] {tenant, id.toString()}));
    }

    /** 冲销固定最初实际接受的原过账修订，争议候选不能成为原件。 */
    public Optional<VoucherOperation> firstAcceptedPosting(String tenant, UUID id) {
        return restoreFirstAcceptedPosting(
                tenant,
                id,
                sqlMapper.firstAcceptedPostingRows(new Object[] {tenant, id.toString()}));
    }

    private static void requireRevision(VoucherOperation value, String tenant, UUID id, long version) {
        if (!value.input().command().tenantId().equals(tenant) || !value.input().command().id().equals(id) || value.version() != version) {
            throw new IllegalStateException("Persisted voucher revision identity is inconsistent");
        }
    }

    private static boolean posted(VoucherObservation value) { return value != null && (value.status() == VoucherObservation.Status.POSTED || value.status() == VoucherObservation.Status.REVERSED); }

    /**
     * 只把裁决所需的历史过账边界交给领域模型。
     *
     * @author owlzhangfq@gmail.com
     */
    public record DisputeEvidence(VoucherObservation originalPosting, boolean postingObserved) {}

    /** 相同业务轮次不允许通过重试另建一个凭证命令。 */
    public Optional<VoucherOperation> forRound(
            String tenant, UUID applicationId, int round, VoucherCommand.Kind kind) {
        return SqlRows.map(
                        sqlMapper.forRound(tenant, applicationId.toString(), round, kind.name()),
                        row())
                .stream()
                .findFirst();
    }

    /** 未经明确有效过账裁决的历史冲销或争议，在重新查询期间仍需冻结原借款。 */
    public boolean requiresAdvanceReview(String tenant, UUID id) {
        return SqlRows.map(
                        sqlMapper.requiresAdvanceReview(tenant, id.toString()),
                        row -> json.read(row.getString("state_json"), VoucherOperation.class))
                .stream()
                .anyMatch(
                        value ->
                                value.reversalId() != null
                                        || value.status() == VoucherOperation.Status.REVERSED
                                        || value.status() == VoucherOperation.Status.RECONCILING);
    }

    /** 每批只扫描十个已到期任务，不在扫描中加载敏感凭证正文。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), timestamp(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 两种业务的数据库绑定都来自明确用途，支付凭证使用原支付用途。 */
    public static BusinessReference.Type businessType(VoucherCommand command) {
        return command.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE || command.kind() == VoucherCommand.Kind.PAYMENT
                && command.payment().command().purpose() == PaymentCommand.Purpose.EMPLOYEE_ADVANCE ? BusinessReference.Type.ADVANCE_REQUEST : BusinessReference.Type.EXPENSE;
    }

    private Function<SqlRow, VoucherOperation> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), VoucherOperation.class);
            var command = value.input().command();
            var binding = command.binding();
            if (!value.input()
                            .equals(
                                    json.read(
                                            row.getString("input_json"),
                                            VoucherOperation.Input.class))
                    || !command.digest().equals(row.getString("command_digest"))
                    || !command.tenantId().equals(row.getString("tenant_id"))
                    || !command.id().toString().equals(row.getString("id"))
                    || !businessType(command).name().equals(row.getString("business_type"))
                    || !binding.businessId().toString().equals(row.getString("business_id"))
                    || !binding.applicationId().toString().equals(row.getString("application_id"))
                    || binding.roundNo() != row.getInt("round_no")
                    || !command.kind().name().equals(row.getString("kind"))
                    || binding.applicationVersion() != row.getLong("application_version")
                    || binding.businessVersion() != row.getLong("business_version")
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || value.highestRevision() != row.getLong("highest_revision")
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(
                            value.reversalId() == null ? null : value.reversalId().toString(),
                            row.getString("reversal_id")))
                throw new IllegalStateException(
                        "Persisted voucher operation identity is inconsistent");
            return value;
        };
    }

    private void append(VoucherOperation value) {
        sqlMapper.append(
                value.input().command().tenantId(),
                value.input().command().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Voucher operation input or version changed"); }

    /**
     * 候选仅携带租户与操作标识，领取后重新读取实际命令。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 没有原业务事实的历史调用保留空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }

    /** 从不可变历史行恢复 disputeEvidence 的领域证据。 */
    private DisputeEvidence restoreDisputeEvidence(
            String tenant, UUID id, java.util.List<SqlRow> persistedRows) {
        VoucherObservation originalPosting = null;
        boolean postingObserved = false;
        for (var rows : persistedRows) {
            var value = json.read(rows.getString("state_json"), VoucherOperation.class);
            requireRevision(value, tenant, id, rows.getLong("version"));
            if (originalPosting == null && posted(value.observation()))
                originalPosting = value.observation();
            postingObserved |=
                    posted(value.observation()) || posted(value.conflictingObservation());
        }
        return new DisputeEvidence(originalPosting, postingObserved);
    }

    /** 从不可变历史行恢复 firstAcceptedPosting 的领域证据。 */
    private Optional<VoucherOperation> restoreFirstAcceptedPosting(
            String tenant, UUID id, java.util.List<SqlRow> persistedRows) {
        for (var rows : persistedRows) {
            var value = json.read(rows.getString("state_json"), VoucherOperation.class);
            requireRevision(value, tenant, id, rows.getLong("version"));
            if (posted(value.observation())) return Optional.of(value);
        }
        return Optional.<VoucherOperation>empty();
    }
}
