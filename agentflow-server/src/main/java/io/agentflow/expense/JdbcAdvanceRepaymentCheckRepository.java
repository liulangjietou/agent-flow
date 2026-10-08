package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.AdvanceRepaymentCheckRepositoryMapper;
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
 * 还款只读队列保留不可变输入及每次修订，所有写入加入原业务事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceRepaymentCheckRepository {
    private final AdvanceRepaymentCheckRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 复用平台事务及 JSON，不在仓储执行外部查询。 */
    public JdbcAdvanceRepaymentCheckRepository(
            AdvanceRepaymentCheckRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 首次请求先保存，实际读取只能消费数据库中可见的队列。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceRepaymentCheck value) {
        if (value.version() != 1 || value.status() != AdvanceRepaymentCheck.Status.QUEUED)
            throw conflict();
        var input = value.input();
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                input.tenantId(),
                input.id().toString(),
                input.request().advanceId().toString(),
                input.paymentId().toString(),
                input.paymentVersion(),
                input.requestedBy(),
                input.request().receiptReference(),
                json.write(input),
                json.write(value),
                timestamp(input.requestedAt()),
                timestamp(value.updatedAt()));
        append(value);
    }

    /** 原输入及版本比较拒绝迟到完成、重复确认和任务串换。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(AdvanceRepaymentCheck value) {
        var input = value.input();
        int count =
                sqlMapper.update(
                        value.version(),
                        value.status().name(),
                        json.write(value),
                        timestamp(value.updatedAt()),
                        timestamp(value.leaseUntil()),
                        input.tenantId(),
                        input.id().toString(),
                        value.version() - 1,
                        json.write(input));
        if (count != 1) throw conflict();
        append(value);
    }

    /** 按认证或队列租户定位，不从任务编号推断权限。 */
    public Optional<AdvanceRepaymentCheck> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 每位财务只使用自己最新明确读取的凭据。 */
    public Optional<AdvanceRepaymentCheck> latest(String tenant, UUID advanceId, String actor) {
        return SqlRows.map(sqlMapper.latest(tenant, advanceId.toString(), actor), row()).stream()
                .findFirst();
    }

    /** 同一原凭据的已完成读取用于跨操作者拒绝旧事实，失败的传输不成为资金结论。 */
    public List<AdvanceRepaymentCheck> receiptHistory(
            String tenant, UUID advanceId, String reference) {
        return SqlRows.map(
                sqlMapper.receiptHistory(tenant, advanceId.toString(), reference), row());
    }

    /** 租约超时也进入有界扫描，由应用服务标记失败。 */
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

    private Function<SqlRow, AdvanceRepaymentCheck> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), AdvanceRepaymentCheck.class);
            var input = value.input();
            if (!input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.request().advanceId().toString().equals(row.getString("advance_id"))
                    || !input.paymentId().toString().equals(row.getString("payment_id"))
                    || input.paymentVersion() != row.getLong("payment_version")
                    || !input.requestedBy().equals(row.getString("requested_by"))
                    || !input.request()
                            .receiptReference()
                            .equals(row.getString("receipt_reference"))
                    || !input.equals(
                            json.read(
                                    row.getString("input_json"), AdvanceRepaymentCheck.Input.class))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || !input.requestedAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            value.leaseUntil(), instant(row.getTimestamp("lease_until"))))
                throw new IllegalStateException(
                        "Persisted repayment check identity is inconsistent");
            return value;
        };
    }

    private void append(AdvanceRepaymentCheck value) {
        sqlMapper.append(
                value.input().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Repayment check input or version changed"); }

    /**
     * 扫描仅返回身份，原件按租约领取后再加载。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 缺少原业务事实时保持空值，不继承工作线程残留值。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
