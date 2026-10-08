package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseSettlementRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 同一报销只形成一份核销账本，轮次和外部依据不能被重试替换。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseSettlementRepository {
    private final ExpenseSettlementRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 资源、预算登记和结算修订共用原业务事务。 */
    public JdbcExpenseSettlementRepository(
            ExpenseSettlementRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 仅接受未消费的新结算，不通过恢复对象伪造已经完成的预算事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseSettlement value) {
        if (!ExpenseSettlement.queue(value.input(), value.createdAt()).equals(value))
            throw conflict();
        var input = value.input();
        var source = input.source();
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                source.tenantId(),
                source.businessId().toString(),
                source.applicationId().toString(),
                source.roundNo(),
                source.applicationVersion(),
                source.businessVersion(),
                id(input.voucherOperationId()),
                input.payment() == null ? null : id(input.payment().operationId()),
                json.write(input),
                json.write(value),
                Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()));
        append(value);
    }

    /** 前版本与全部原输入匹配后更新，追加证据失败会一并回滚资源和预算命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseSettlement value) {
        var source = value.input().source();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.resourcesConsumed(),
                        id(value.budgetOperationId()),
                        value.issue(),
                        Timestamp.from(value.updatedAt()),
                        source.tenantId(),
                        source.businessId().toString(),
                        value.version() - 1,
                        json.write(value.input()),
                        Timestamp.from(value.createdAt()),
                        value.resourcesConsumed());
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 仓储总以独立租户列定位，不能凭输入 JSON 选择另一个租户。 */
    public Optional<ExpenseSettlement> find(String tenant, UUID reportId) {
        return SqlRows.map(sqlMapper.find(tenant, reportId.toString()), row()).stream().findFirst();
    }

    /** 原消息读取固定修订，后来的重试或裁决不能替换已经发生的结算事实。 */
    public Optional<ExpenseSettlement> revision(String tenant, UUID reportId, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, reportId.toString(), version),
                        row -> {
                            var value =
                                    json.read(row.getString("state_json"), ExpenseSettlement.class);
                            var source = value.input().source();
                            if (!source.tenantId().equals(tenant)
                                    || !source.businessId().equals(reportId)
                                    || value.version() != version) {
                                throw new IllegalStateException(
                                        "Persisted expense settlement revision identity is"
                                            + " inconsistent");
                            }
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 本地核销不含网络等待，消费者用原报销锁完成有界短事务。 */
    public List<Candidate> pending() {
        return SqlRows.map(
                sqlMapper.pending(),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("report_id")),
                                row.getLong("version"),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 轮转游标避免未满足零应付条件的前十份凭证阻塞后续升级补登，进程重启可安全重扫。 */
    public List<RecoveryCandidate> recoveryCandidates(RecoveryCandidate after) {

        var args = new java.util.ArrayList<Object>();
        if (after != null) {

            args.addAll(
                    List.of(
                            after.kind().name(),
                            after.kind().name(),
                            after.tenantId(),
                            after.kind().name(),
                            after.tenantId(),
                            after.id().toString()));
        }
        return SqlRows.map(
                sqlMapper.recoveryCandidatesQuery((after != null), args.toArray()),
                row ->
                        new RecoveryCandidate(
                                FundingKind.valueOf(row.getString("kind")),
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("report_id")),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private Function<SqlRow, ExpenseSettlement> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), ExpenseSettlement.class);
            var input = value.input();
            var source = input.source();
            if (!input.equals(json.read(row.getString("input_json"), ExpenseSettlement.Input.class))
                    || !source.tenantId().equals(row.getString("tenant_id"))
                    || !source.businessId().toString().equals(row.getString("report_id"))
                    || !source.applicationId().toString().equals(row.getString("application_id"))
                    || !source.businessType().name().equals(row.getString("business_type"))
                    || source.roundNo() != row.getInt("round_no")
                    || source.applicationVersion() != row.getLong("application_version")
                    || source.businessVersion() != row.getLong("financial_version")
                    || !Objects.equals(
                            id(input.voucherOperationId()), row.getString("voucher_operation_id"))
                    || !Objects.equals(
                            input.payment() == null ? null : id(input.payment().operationId()),
                            row.getString("payment_operation_id"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.resourcesConsumed() != row.getBoolean("resources_consumed")
                    || !Objects.equals(
                            id(value.budgetOperationId()), row.getString("budget_operation_id"))
                    || !Objects.equals(value.issue(), row.getString("issue"))
                    || !value.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant()))
                throw new IllegalStateException(
                        "Persisted expense settlement identity is inconsistent");
            return value;
        };
    }

    private void append(ExpenseSettlement value) {
        var source = value.input().source();
        sqlMapper.append(
                source.tenantId(),
                source.businessId().toString(),
                value.version(),
                json.write(value));
    }

    private static String id(UUID value) { return value == null ? null : value.toString(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense settlement input or version changed"); }

    /**
     * 调度候选不携带金额、回单或其他敏感数据。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID reportId,
            long version,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧候选没有业务关联时保留空值，工作器不得借用调用线程。 */
        public Candidate(String tenantId, UUID reportId, long version, String traceId) { this(tenantId, reportId, version, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID reportId, long version) { this(tenantId, reportId, version, null); }
    }

    /**
     * 仅指向已持久化的会计或资金依据。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum FundingKind {
        PAYMENT,
        VOUCHER,
        ZERO
    }

    /**
     * 补登扫描游标不携带财务金额或回单。
     *
     * @author owlzhangfq@gmail.com
     */
    public record RecoveryCandidate(
            FundingKind kind,
            String tenantId,
            UUID reportId,
            UUID id,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧候选没有业务关联时保留空值，工作器不得借用调用线程。 */
        public RecoveryCandidate(FundingKind kind, String tenantId, UUID reportId, UUID id, String traceId) { this(kind, tenantId, reportId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public RecoveryCandidate(FundingKind kind, String tenantId, UUID reportId, UUID id) { this(kind, tenantId, reportId, id, null); }
    }
}
