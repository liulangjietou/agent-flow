package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.mapper.VoucherReversalCheckRepositoryMapper;
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
 * 独立冲销只读队列和不可变修订，同一原件的不同操作者观察均保留。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherReversalCheckRepository {
    private final VoucherReversalCheckRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 仓储只处理本地事实，不执行外部资金或会计操作。 */
    public JdbcVoucherReversalCheckRepository(
            VoucherReversalCheckRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 意图先加入原申请事务，后台只处理已提交的原凭证。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherReversalCheck value) {
        if (value.version() != 1 || value.status() != VoucherReversalCheck.Status.QUEUED)
            throw conflict();
        var input = value.input();
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                input.tenantId(),
                input.id().toString(),
                input.request().command().id().toString(),
                input.originalVersion(),
                input.requestedBy(),
                json.write(input),
                json.write(value),
                timestamp(input.requestedAt()),
                timestamp(value.updatedAt()));
        append(value);
    }

    /** 版本和原输入共同约束，迟到任务不能替换当前租约或二次裁决。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(VoucherReversalCheck value) {
        var input = value.input();
        int changed =
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
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 按租户定位精确查询，不从编号推断业务授权。 */
    public Optional<VoucherReversalCheck> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 财务只办理自己对这笔原凭证最新明确发起的复核。 */
    public Optional<VoucherReversalCheck> latest(String tenant, UUID operationId, String actor) {
        return SqlRows.map(sqlMapper.latest(tenant, operationId.toString(), actor), row()).stream()
                .findFirst();
    }

    /** 不同财务的已完成观察共同约束最高外部版本和既有反向分录事实。 */
    public List<VoucherReversalCheck> history(String tenant, UUID operationId) {
        return SqlRows.map(sqlMapper.history(tenant, operationId.toString()), row());
    }

    /** 每次只领取有限数量，并由应用服务处理超时旧租约。 */
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

    private Function<SqlRow, VoucherReversalCheck> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), VoucherReversalCheck.class);
            var input = value.input();
            if (!input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.request()
                            .command()
                            .id()
                            .toString()
                            .equals(row.getString("operation_id"))
                    || input.originalVersion() != row.getLong("original_version")
                    || !input.requestedBy().equals(row.getString("requested_by"))
                    || !input.equals(
                            json.read(
                                    row.getString("input_json"), VoucherReversalCheck.Input.class))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || !input.requestedAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            value.leaseUntil(), instant(row.getTimestamp("lease_until"))))
                throw new IllegalStateException(
                        "Persisted voucher reversal identity is inconsistent");
            return value;
        };
    }

    private void append(VoucherReversalCheck value) {
        sqlMapper.append(
                value.input().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }

    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Voucher reversal version or original input changed"); }

    /**
     * 扫描只携带租户与任务标识。
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
