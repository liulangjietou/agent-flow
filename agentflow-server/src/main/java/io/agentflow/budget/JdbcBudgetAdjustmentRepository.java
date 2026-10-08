package io.agentflow.budget;

import io.agentflow.budget.mapper.BudgetAdjustmentRepositoryMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 预算调整申请与审批状态分别持久化，每次变更追加不可覆盖的原始版本。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetAdjustmentRepository implements BudgetAdjustmentRepository {
    private final BudgetAdjustmentRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 共用平台事务管理器，跨聚合编排可以一并回滚。 */
    public JdbcBudgetAdjustmentRepository(
            BudgetAdjustmentRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 所有跨聚合财务编排共用相同锁顺序，不能把锁留在某一种后台任务仓储。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, UUID requestId) {
        var application = sqlMapper.lock(tenant, requestId.toString());
        if (application.isEmpty())
            throw new DomainException("NOT_FOUND", "Budget adjustment not found");
        sqlMapper.lock2(tenant, application.get(0));
        sqlMapper.lock3(tenant, requestId.toString());
    }

    @Override
    @Transactional
    public void create(BudgetAdjustmentRequest request, String actor) {
        requireAudit(actor, "CREATE");
        if (request.version() != 1
                || !request.rounds().isEmpty()
                || !request.employeeId().equals(actor)) throw conflict();
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
            BudgetAdjustmentRequest request, long expectedVersion, String actor, String operation) {
        requireAudit(actor, operation);
        if (request.version() != expectedVersion + 1) throw conflict();
        lock(request.tenantId(), request.id());
        var before =
                find(request.tenantId(), request.id())
                        .orElseThrow(JdbcBudgetAdjustmentRepository::conflict);
        requireTransition(before, request, expectedVersion, actor, operation);
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

    private static void requireTransition(BudgetAdjustmentRequest before, BudgetAdjustmentRequest changed,
                                          long expectedVersion, String actor, String operation) {
        if (before.version() != expectedVersion || before.approval() != null || !before.applicationId().equals(changed.applicationId())
                || !before.employeeId().equals(changed.employeeId()) || changed.rounds().size() < before.rounds().size()
                || !changed.rounds().subList(0, before.rounds().size()).equals(before.rounds())) throw conflict();
        boolean accepted = switch (operation) {
            case "REVISE" -> actor.equals(before.employeeId()) && changed.rounds().equals(before.rounds()) && changed.approval() == null;
            case "SUBMIT" -> actor.equals(before.employeeId()) && changed.content().equals(before.content()) && changed.approval() == null
                    && changed.rounds().size() == before.rounds().size() + 1
                    && changed.currentRound().submittedRequestVersion() == before.version();
            case "APPROVE" -> changed.rounds().equals(before.rounds()) && changed.content().equals(before.content())
                    && changed.approval() != null && changed.approval().approvedBy().equals(actor);
            default -> false;
        };
        if (!accepted) throw conflict();
    }

    @Override
    public Optional<BudgetAdjustmentRequest> find(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.find(tenantId, id.toString()), this::restore).stream()
                .findFirst();
    }

    private void append(BudgetAdjustmentRequest request, String actor, String operation) {
        sqlMapper.append(
                request.tenantId(),
                request.id().toString(),
                request.version(),
                actor,
                operation,
                json.write(request.state()));
    }

    private BudgetAdjustmentRequest restore(SqlRow row) {
        var state = json.read(row.getString("state_json"), BudgetAdjustmentRequest.State.class);
        if (!state.id().toString().equals(row.getString("id")) || !state.tenantId().equals(row.getString("tenant_id"))
                || !state.applicationId().toString().equals(row.getString("application_id"))
                || !state.employeeId().equals(row.getString("employee_id")) || state.version() != row.getLong("version")) {
            throw new IllegalStateException("Persisted budget adjustment binding is inconsistent");
        }
        return BudgetAdjustmentRequest.restore(state);
    }

    private static void requireAudit(String actor, String operation) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException("INVALID_BUDGET_ADJUSTMENT_AUDIT", "Budget adjustment changes require an actor and operation");
        }
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget adjustment binding or version has changed"); }
}
