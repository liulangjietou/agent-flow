package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePlanRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 事前计划与审批状态分别持久化，每次变更追加不可覆盖的原始版本。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePlanRepository implements ExpensePlanRepository {
    private final ExpensePlanRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 共用平台事务管理器，跨聚合编排可以一并回滚。 */
    public JdbcExpensePlanRepository(ExpensePlanRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 所有跨聚合财务编排共用相同锁顺序，不能把锁留在某一种后台任务仓储。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, UUID planId) {
        var application = sqlMapper.lock(tenant, planId.toString());
        if (application.isEmpty()) throw new DomainException("NOT_FOUND", "Expense plan not found");
        sqlMapper.lock2(tenant, application.get(0));
        sqlMapper.lock3(tenant, planId.toString());
    }

    @Override
    @Transactional
    public void create(ExpensePlan plan, String actor) {
        requireAudit(actor, "CREATE");
        if (plan.version() != 1 || !plan.rounds().isEmpty()) throw conflict();
        int inserted =
                sqlMapper.create(
                        plan.id().toString(),
                        json.write(plan.state()),
                        plan.tenantId(),
                        plan.applicationId().toString(),
                        plan.employeeId(),
                        plan.id().toString());
        if (inserted != 1) throw conflict();
        append(plan, actor, "CREATE");
    }

    @Override
    @Transactional
    public void update(ExpensePlan plan, long expectedVersion, String actor, String operation) {
        requireAudit(actor, operation);
        if (plan.version() != expectedVersion + 1) throw conflict();
        int updated =
                sqlMapper.update(
                        plan.version(),
                        json.write(plan.state()),
                        plan.tenantId(),
                        plan.id().toString(),
                        plan.applicationId().toString(),
                        plan.employeeId(),
                        expectedVersion);
        if (updated != 1) throw conflict();
        append(plan, actor, operation);
    }

    @Override
    public Optional<ExpensePlan> find(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.find(tenantId, id.toString()), this::restore).stream()
                .findFirst();
    }

    private void append(ExpensePlan plan, String actor, String operation) {
        sqlMapper.append(
                plan.tenantId(),
                plan.id().toString(),
                plan.version(),
                actor,
                operation,
                json.write(plan.state()));
    }

    private ExpensePlan restore(SqlRow row) {
        var state = json.read(row.getString("state_json"), ExpensePlan.State.class);
        if (!state.id().toString().equals(row.getString("id")) || !state.tenantId().equals(row.getString("tenant_id"))
                || !state.applicationId().toString().equals(row.getString("application_id"))
                || !state.employeeId().equals(row.getString("employee_id")) || state.version() != row.getLong("version")) {
            throw new IllegalStateException("Persisted expense plan binding is inconsistent");
        }
        return ExpensePlan.restore(state);
    }

    private static void requireAudit(String actor, String operation) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException("INVALID_EXPENSE_PLAN_AUDIT", "Plan changes require an actor and operation");
        }
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense plan binding or version has changed"); }
}
