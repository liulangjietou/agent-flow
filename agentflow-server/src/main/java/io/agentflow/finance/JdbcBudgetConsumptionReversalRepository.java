package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseResourceAdjustment;
import io.agentflow.finance.mapper.BudgetConsumptionReversalRepositoryMapper;
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
 * 预算实际冲正的独立 outbox，原消费修订和已授权报销调整必须先存在。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetConsumptionReversalRepository {
    private final BudgetConsumptionReversalRepositoryMapper sqlMapper;
    private final JsonUtil json;

    public JdbcBudgetConsumptionReversalRepository(
            BudgetConsumptionReversalRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 初次只登记原授权的排队命令，不允许从快照直接制造外部成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetConsumptionReversalOperation value) {
        var input = value.input();
        var command = input.command();
        var tenant = command.source().tenantId();
        if (!value.equals(BudgetConsumptionReversalOperation.queue(input, value.createdAt()))
                || !command.id().equals(command.adjustmentId())) throw conflict();
        var adjustment =
                SqlRows.map(
                                sqlMapper.create(tenant, command.adjustmentId().toString()),
                                row ->
                                        json.read(
                                                row.getString("state_json"),
                                                ExpenseResourceAdjustment.class))
                        .stream()
                        .findFirst()
                        .orElseThrow(JdbcBudgetConsumptionReversalRepository::conflict);
        if (!adjustment.input().budget().equals(input)
                || adjustment.status() != ExpenseResourceAdjustment.Status.WAITING_BUDGET
                || adjustment.version() != 1) throw conflict();
        sqlMapper.create2(
                DiagnosticContext.capture().traceId(),
                tenant,
                command.id().toString(),
                command.source().id().toString(),
                input.consumedVersion(),
                json.write(input),
                command.digest(),
                json.write(value),
                timestamp(value.createdAt()),
                timestamp(value.updatedAt()),
                timestamp(value.nextAttemptAt()));
        append(value);
    }

    /** 原命令与目标保持不变，已安全结束的办理不可被迟到工作器重新执行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetConsumptionReversalOperation value) {
        var input = value.input();
        var command = input.command();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        timestamp(value.updatedAt()),
                        timestamp(value.nextAttemptAt()),
                        timestamp(value.leaseUntil()),
                        command.source().tenantId(),
                        command.id().toString(),
                        value.version() - 1,
                        json.write(input),
                        command.digest(),
                        timestamp(value.createdAt()),
                        command.source().tenantId(),
                        command.adjustmentId().toString());
        if (changed != 1) throw conflict();
        append(value);
    }

    public Optional<BudgetConsumptionReversalOperation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 接受的预算结果和结束证明引用确切修订，不能被当前结果覆盖。 */
    public Optional<BudgetConsumptionReversalOperation> revision(
            String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            BudgetConsumptionReversalOperation.class);
                            if (!value.input().command().source().tenantId().equals(tenant)
                                    || !value.input().command().id().equals(id)
                                    || value.version() != version) throw inconsistent();
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 消息回溯只读取同一原指令的准确修订，不将当前结果替换为历史事实。 */
    public List<BudgetConsumptionReversalOperation> revisions(String tenant, UUID id) {
        return SqlRows.map(
                sqlMapper.revisions(tenant, id.toString()),
                row -> {
                    var value =
                            json.read(
                                    row.getString("state_json"),
                                    BudgetConsumptionReversalOperation.class);
                    if (!value.input().command().source().tenantId().equals(tenant)
                            || !value.input().command().id().equals(id)
                            || value.version() != row.getLong("version")) throw inconsistent();
                    return value;
                });
    }

    /** 未知按持久退避查询，租约超时不得被扫描器改成首次发送。 */
    public List<Candidate> due(Instant at) {
        return SqlRows.map(
                sqlMapper.due(timestamp(at), timestamp(at)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private Function<SqlRow, BudgetConsumptionReversalOperation> row() {
        return row -> {
            var value =
                    json.read(
                            row.getString("state_json"), BudgetConsumptionReversalOperation.class);
            var input = value.input();
            var command = input.command();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    BudgetConsumptionReversalOperation.Input.class))
                    || !command.source().tenantId().equals(row.getString("tenant_id"))
                    || !command.id().toString().equals(row.getString("id"))
                    || !command.id().equals(command.adjustmentId())
                    || !command.source().id().toString().equals(row.getString("consumption_id"))
                    || input.consumedVersion() != row.getLong("consumed_version")
                    || !command.digest().equals(row.getString("command_digest"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || !value.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())
                    || !Objects.equals(
                            value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(
                            value.leaseUntil(), instant(row.getTimestamp("lease_until"))))
                throw inconsistent();
            return value;
        };
    }

    private void append(BudgetConsumptionReversalOperation value) {
        var command = value.input().command();
        sqlMapper.append(
                command.source().tenantId(),
                command.id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }

    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget reversal input, adjustment or version changed"); }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted budget consumption reversal identity is inconsistent"); }

    /**
     * 扫描只传固定租户和命令编号。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 旧候选没有业务关联时保留空值，工作器不得借用调用线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
