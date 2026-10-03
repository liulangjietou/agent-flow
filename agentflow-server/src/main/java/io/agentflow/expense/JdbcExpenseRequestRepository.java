package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 事前申请仓储固定原批准行与容差，关闭不删除未结算占用。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseRequestRepository implements ExpenseRequestRepository {
    private static final FinancialResourceStore.Kind KIND = FinancialResourceStore.Kind.PRIOR_REQUEST;
    private final FinancialResourceStore store;
    private final JsonUtil json;
    private final JdbcTemplate jdbc;

    /** 使用共享的乐观锁和版本证据，额度规则由事前申请聚合拥有。 */
    public JdbcExpenseRequestRepository(FinancialResourceStore store, JsonUtil json, JdbcTemplate jdbc) { this.store = store; this.json = json; this.jdbc = jdbc; }

    @Override @Transactional
    public void create(ExpenseRequest request, String actor) {
        if (request.closed() || request.balances().values().stream().anyMatch(balance -> !balance.reservations().isEmpty() || !balance.consumptions().isEmpty())) {
            throw new DomainException("INVALID_PRIOR_REQUEST", "A new prior request must have unallocated approved lines");
        }
        var approved = jdbc.queryForList("SELECT id FROM approval_application WHERE tenant_id=? AND id=? AND created_by=? AND status='APPROVED' FOR UPDATE",
                String.class, request.tenantId(), request.applicationId().toString(), request.employeeId());
        if (approved.isEmpty()) throw new DomainException("PRIOR_REQUEST_NOT_APPROVED", "Prior request credit requires the applicant's approved application");
        store.create(KIND, stored(request), actor);
    }

    @Override @Transactional
    public void update(ExpenseRequest request, long expectedVersion, String actor, String operation) {
        var row = stored(request); store.update(KIND, row, expectedVersion, actor, operation);
        store.replaceAmountUses(KIND, row, request.balances());
    }

    @Override @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lock(String tenantId, UUID id) {
        if (jdbc.queryForList("SELECT id FROM finance_resource WHERE tenant_id=? AND resource_type='PRIOR_REQUEST' AND id=? FOR UPDATE",
                String.class, tenantId, id.toString()).isEmpty()) {
            throw new DomainException("NOT_FOUND", "Prior request credit not found");
        }
    }

    @Override
    public Optional<ExpenseRequest> find(String tenantId, UUID id) {
        return store.find(KIND, tenantId, id).map(this::decode);
    }

    @Override
    public java.util.Map<UUID, ExpenseRequest> findAll(String tenantId, java.util.Collection<UUID> ids) {
        return store.findAll(KIND, tenantId, ids).stream().map(this::decode)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ExpenseRequest::id, value -> value));
    }

    private ExpenseRequest decode(FinancialResourceStore.Stored row) {
        var value = ExpenseRequest.restore(json.read(row.state(), ExpenseRequest.State.class));
        row.requireConsistent(stored(value)); return value;
    }

    private FinancialResourceStore.Stored stored(ExpenseRequest request) {
        return new FinancialResourceStore.Stored(request.id(), request.tenantId(), request.employeeId(), request.applicationId().toString(), request.version(),
                json.write(new Context(request.legalEntityId(), request.approvedLines())), json.write(request.state()));
    }

    /**
     * 原批准及其制度容差是不可变的核销授权背景。
     * @author owlzhangfq@gmail.com
     */
    private record Context(UUID legalEntityId, List<ExpenseRequest.ApprovedLine> approvedLines) { }
}
