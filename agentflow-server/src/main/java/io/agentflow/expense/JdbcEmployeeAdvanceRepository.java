package io.agentflow.expense;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.EmployeeAdvanceRepositoryMapper;
import io.agentflow.finance.Money;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 借款仓储固定放款金额和来源；余额变更、规范化占用和版本证据同事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcEmployeeAdvanceRepository implements EmployeeAdvanceRepository {
    private static final FinancialResourceStore.Kind KIND = FinancialResourceStore.Kind.ADVANCE;
    private final FinancialResourceStore store;
    private final JsonUtil json;
    private final EmployeeAdvanceRepositoryMapper sqlMapper;

    /** 复用存储协议，不复用事前申请的关闭或容差规则。 */
    public JdbcEmployeeAdvanceRepository(
            FinancialResourceStore store,
            JsonUtil json,
            EmployeeAdvanceRepositoryMapper sqlMapper) {
        this.store = store;
        this.json = json;
        this.sqlMapper = sqlMapper;
    }

    @Override
    @Transactional
    public void create(EmployeeAdvance advance, String actor) {
        if (!advance.balance().reservations().isEmpty()
                || !advance.balance().consumptions().isEmpty()
                || !advance.repayments().isEmpty()
                || !advance.disbursementReturns().isEmpty())
            throw new DomainException(
                    "INVALID_ADVANCE", "A new advance must be an unallocated actual payment");
        store.create(KIND, stored(advance), actor);
        sqlMapper.create(
                advance.tenantId(),
                advance.id().toString(),
                advance.employeeId(),
                advance.legalEntityId().toString(),
                advance.balance().limit().currency(),
                advance.paidOn(),
                advance.dueOn(),
                DiagnosticContext.capture().traceId());
    }

    @Override @Transactional
    public void update(EmployeeAdvance advance, long expectedVersion, String actor, String operation) {
        var row = stored(advance); store.update(KIND, row, expectedVersion, actor, operation);
        store.replaceAmountUses(KIND, row, Map.of(0, advance.balance()));
    }

    @Override
    public Optional<EmployeeAdvance> find(String tenantId, UUID id) {
        return store.find(KIND, tenantId, id).map(this::decode);
    }

    @Override
    public java.util.Map<UUID, EmployeeAdvance> findAll(String tenantId, java.util.Collection<UUID> ids) {
        return store.findAll(KIND, tenantId, ids).stream().map(this::decode)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(EmployeeAdvance::id, value -> value));
    }

    @Override
    public java.util.List<EmployeeAdvance> findOwnedByPaidOn(
            String tenant,
            String employee,
            UUID entity,
            String currency,
            PaidCursor after,
            int limit) {
        var parameters =
                new java.util.ArrayList<Object>(
                        java.util.List.of(tenant, employee, entity.toString(), currency));

        if (after != null) {
            parameters.add(after.paidOn());
            parameters.add(after.paidOn());
            parameters.add(after.id().toString());
        }
        parameters.add(limit);
        return SqlRows.map(
                sqlMapper.findOwnedByPaidOnQuery(after == null, parameters.toArray()),
                row -> {
                    var value =
                            decode(
                                    new FinancialResourceStore.Stored(
                                            UUID.fromString(row.getString("id")),
                                            row.getString("tenant_id"),
                                            row.getString("owner_id"),
                                            row.getString("source_reference"),
                                            row.getLong("version"),
                                            row.getString("context_json"),
                                            row.getString("state_json")));
                    if (!value.employeeId().equals(employee)
                            || !value.legalEntityId().equals(entity)
                            || !value.balance().limit().currency().equals(currency)
                            || !value.paidOn().equals(row.getDate("paid_on").toLocalDate()))
                        throw new IllegalStateException("Advance ordering facts are inconsistent");
                    return value;
                });
    }

    private EmployeeAdvance decode(FinancialResourceStore.Stored row) {
        var value = EmployeeAdvance.restore(json.read(row.state(), EmployeeAdvance.State.class));
        row.requireConsistent(stored(value)); return value;
    }

    private FinancialResourceStore.Stored stored(EmployeeAdvance advance) {
        return new FinancialResourceStore.Stored(advance.id(), advance.tenantId(), advance.employeeId(), advance.paymentReference(), advance.version(),
                json.write(new Context(advance.legalEntityId(), advance.balance().limit(), advance.paidOn(), advance.dueOn())), json.write(advance.state()));
    }

    /**
     * 放款额度、法人及约定日期不可由冲销操作改写。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Context(
            UUID legalEntityId, Money paidAmount, LocalDate paidOn, LocalDate dueOn) {}
}
