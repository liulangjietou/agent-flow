package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.procurement.mapper.ProcurementPaymentRepositoryMapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 采购付款申请与审批状态分别持久化，每次变更追加不可覆盖的原始版本。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcProcurementPaymentRepository implements ProcurementPaymentRepository {
    private final ProcurementPaymentRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 共用平台事务管理器，跨聚合编排可以一并回滚。 */
    public JdbcProcurementPaymentRepository(
            ProcurementPaymentRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 所有跨聚合财务编排共用相同锁顺序，不能把锁留在某一种后台任务仓储。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, UUID requestId) {
        var application = sqlMapper.lock(tenant, requestId.toString());
        if (application.isEmpty())
            throw new DomainException("NOT_FOUND", "Procurement payment not found");
        sqlMapper.lock2(tenant, application.get(0));
        sqlMapper.lock3(tenant, requestId.toString());
    }

    @Override
    @Transactional
    public void create(ProcurementPaymentRequest request, String actor) {
        requireAudit(actor, "CREATE");
        if (request.version() != 1 || !request.rounds().isEmpty()) throw conflict();
        int inserted =
                sqlMapper.create(
                        request.id().toString(),
                        json.write(request.state()),
                        request.tenantId(),
                        request.applicationId().toString(),
                        request.employeeId(),
                        request.id().toString());
        if (inserted != 1) throw conflict();
        append(request, actor, "CREATE");
    }

    @Override
    @Transactional
    public void update(
            ProcurementPaymentRequest request,
            long expectedVersion,
            String actor,
            String operation) {
        requireAudit(actor, operation);
        if (request.version() != expectedVersion + 1) throw conflict();
        int updated =
                sqlMapper.update(
                        request.version(),
                        json.write(request.state()),
                        request.tenantId(),
                        request.id().toString(),
                        request.applicationId().toString(),
                        request.employeeId(),
                        expectedVersion);
        if (updated != 1) throw conflict();
        append(request, actor, operation);
    }

    @Override
    public Optional<ProcurementPaymentRequest> find(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.find(tenantId, id.toString()), this::restore).stream()
                .findFirst();
    }

    private void append(ProcurementPaymentRequest request, String actor, String operation) {
        sqlMapper.append(
                request.tenantId(),
                request.id().toString(),
                request.version(),
                actor,
                operation,
                json.write(request.state()));
    }

    private ProcurementPaymentRequest restore(SqlRow row) {
        var state = json.read(row.getString("state_json"), ProcurementPaymentRequest.State.class);
        if (!state.id().toString().equals(row.getString("id")) || !state.tenantId().equals(row.getString("tenant_id"))
                || !state.applicationId().toString().equals(row.getString("application_id"))
                || !state.employeeId().equals(row.getString("employee_id")) || state.version() != row.getLong("version")) {
            throw new IllegalStateException("Persisted procurement payment binding is inconsistent");
        }
        return ProcurementPaymentRequest.restore(state);
    }

    private static void requireAudit(String actor, String operation) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException("INVALID_PROCUREMENT_PAYMENT_AUDIT", "Procurement payment changes require an actor and operation");
        }
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Procurement payment binding or version has changed"); }
}
