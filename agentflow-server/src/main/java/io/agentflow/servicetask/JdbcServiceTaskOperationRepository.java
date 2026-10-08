package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.servicetask.mapper.ServiceTaskOperationRepositoryMapper;

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
 * 原命令不可改写，领取版本隔离迟到执行者；引擎推进回执与操作结果分别记录。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcServiceTaskOperationRepository {
    private final ServiceTaskOperationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 状态与连续修订写入同一事务。 */
    public JdbcServiceTaskOperationRepository(
            ServiceTaskOperationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 节点激活只入队，同事务失败不会留下可被后台领取的任务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ServiceTaskOperation operation) {
        if (operation.version() != 1
                || operation.status() != ServiceTaskOperation.Status.QUEUED
                || operation.attempts() != 0) throw conflict();
        var input = operation.input();
        var command = input.command();
        var binding = command.binding();
        sqlMapper.create(
                command.tenantId(),
                command.id().toString(),
                binding.applicationId().toString(),
                binding.roundNo(),
                binding.processInstanceId(),
                binding.executionId(),
                binding.nodeId(),
                command.contract().key(),
                command.contract().version(),
                command.contract().digest(),
                input.targetDigest(),
                command.digest(),
                json.write(input),
                json.write(operation),
                timestamp(operation.createdAt()),
                timestamp(operation.updatedAt()),
                timestamp(operation.nextAttemptAt()),
                timestamp(operation.nextAttemptAt()),
                DiagnosticContext.capture().traceId());
        append(operation);
    }

    /** 只有前一领取版本及原输入匹配时更新，输入 JSON、摘要和引擎来源列始终不写回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ServiceTaskOperation operation) {
        var command = operation.input().command();
        Instant pollAt =
                operation.status() == ServiceTaskOperation.Status.APPLIED
                        ? operation.updatedAt()
                        : operation.running() ? operation.leaseUntil() : operation.nextAttemptAt();
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
                        command.tenantId(),
                        command.id().toString(),
                        operation.version() - 1,
                        command.digest(),
                        operation.input().targetDigest());
        if (changed != 1) throw conflict();
        append(operation);
    }

    /** 只调整调度元数据，暂停或暂时停用不伪造一次外发尝试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postpone(Stored current, Instant until) {
        if (current.progress() != Progress.PENDING) return;
        var operation = current.operation();
        var command = operation.input().command();
        if (sqlMapper.postpone(
                        timestamp(until),
                        command.tenantId(),
                        command.id().toString(),
                        operation.version())
                != 1) throw conflict();
    }

    /** ADVANCED 与引擎、申请、通知同事务提交，重启不能重复推进已确认节点。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markProgress(Stored current, Progress progress, Instant now) {
        var operation = current.operation();
        var command = operation.input().command();
        if (progress == Progress.PENDING
                || current.progress() != Progress.PENDING
                || !operation.terminal()
                || progress == Progress.ADVANCED
                        && operation.status() != ServiceTaskOperation.Status.APPLIED)
            throw conflict();
        if (sqlMapper.markProgress(
                        progress.name(),
                        timestamp(now),
                        command.tenantId(),
                        command.id().toString(),
                        operation.version())
                != 1) throw conflict();
    }

    /** 读取带原始索引和摘要的完整状态。 */
    public Optional<Stored> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 使用既有轮次索引按不可变创建时间和标识翻页，查询上限由已校验的入口传入。 */
    public List<Stored> forRound(
            String tenant, UUID applicationId, int roundNo, ServiceTaskOperation after, int limit) {

        if (after == null) {
            return SqlRows.map(
                    sqlMapper.forRoundQuery(
                            new Object[] {tenant, applicationId.toString(), roundNo, limit}),
                    row());
        }
        return SqlRows.map(
                sqlMapper.forRoundQuery2(
                        new Object[] {
                            tenant,
                            applicationId.toString(),
                            roundNo,
                            timestamp(after.createdAt()),
                            timestamp(after.createdAt()),
                            after.input().command().id().toString(),
                            limit
                        }),
                row());
    }

    /** 调用方先取得根到叶申请锁，再锁操作记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Stored> lock(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.lock(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 同一执行令牌到下一个节点时应创建新操作，同一原等待只能登记一次。 */
    public Optional<Stored> atWait(
            String tenant, String processInstanceId, String executionId, String nodeId) {
        return SqlRows.map(sqlMapper.atWait(tenant, processInstanceId, executionId, nodeId), row())
                .stream()
                .findFirst();
    }

    /** 扫描仅返回标识，暂停任务通过 poll_at 退避，不能占住队列头部。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private Function<SqlRow, Stored> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), ServiceTaskOperation.class);
            var input = value.input();
            var command = input.command();
            var binding = command.binding();
            var progress = Progress.valueOf(row.getString("progress"));
            var progressedAt = instant(row.getTimestamp("progressed_at"));
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"), ServiceTaskOperation.Input.class))
                    || !command.tenantId().equals(row.getString("tenant_id"))
                    || !command.id().toString().equals(row.getString("id"))
                    || !binding.applicationId().toString().equals(row.getString("application_id"))
                    || binding.roundNo() != row.getInt("round_no")
                    || !binding.processInstanceId().equals(row.getString("process_instance_id"))
                    || !binding.executionId().equals(row.getString("execution_id"))
                    || !binding.nodeId().equals(row.getString("node_id"))
                    || !command.contract().key().equals(row.getString("operation_key"))
                    || command.contract().version() != row.getLong("operation_version")
                    || !command.contract().digest().equals(row.getString("contract_digest"))
                    || !input.targetDigest().equals(row.getString("target_digest"))
                    || !command.digest().equals(row.getString("command_digest"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || (progress == Progress.PENDING) != (progressedAt == null)
                    || progress != Progress.PENDING && row.getTimestamp("poll_at") != null
                    || progress == Progress.ADVANCED
                            && value.status() != ServiceTaskOperation.Status.APPLIED)
                throw new IllegalStateException(
                        "Persisted service task operation identity is inconsistent");
            return new Stored(value, progress, progressedAt);
        };
    }

    private void append(ServiceTaskOperation operation) {
        sqlMapper.append(
                operation.input().command().tenantId(),
                operation.input().command().id().toString(),
                operation.version(),
                json.write(operation));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Service task operation input, claim or progress changed"); }

    /**
     * @author owlzhangfq@gmail.com
     */
    public enum Progress {
        PENDING,
        ADVANCED,
        STALE
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    public record Stored(ServiceTaskOperation operation, Progress progress, Instant progressedAt) {}

    /**
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务事实不存在时保持空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }
}
