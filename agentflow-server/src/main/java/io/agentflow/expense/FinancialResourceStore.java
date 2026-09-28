package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.ReservedAmount;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 三类已实际接入的财务资源共用版本、不可变背景和审计存储，业务转换仍由各自聚合负责。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class FinancialResourceStore {
    private final JdbcTemplate jdbc;

    /** 与发票互斥键、金额使用明细及审批编排共用同一事务。 */
    public FinancialResourceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 仓储创建必须已处于外层业务事务，初始身份和来源只写一次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(Kind kind, Stored value, String actor) {
        requireAudit(actor, "CREATE");
        if (value.version() != 1) throw conflict();
        try {
            jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES(?,?,?,?,?,1,?,?)",
                    value.tenantId(), kind.name(), value.id().toString(), value.ownerId(), value.sourceReference(), value.context(), value.state());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("FINANCIAL_RESOURCE_EXISTS", "Financial resource source already exists in this tenant"); }
        append(kind, value, actor, "CREATE");
    }

    /** 同一资源只能有一个版本写入成功，身份及外部来源不能被余额更新替换。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Kind kind, Stored value, long expectedVersion, String actor, String operation) {
        requireAudit(actor, operation);
        if (value.version() != expectedVersion + 1) throw conflict();
        int updated = jdbc.update("""
                UPDATE finance_resource SET version=?,state_json=?,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND resource_type=? AND id=? AND owner_id=? AND source_reference=? AND context_json=? AND version=?
                """, value.version(), value.state(), value.tenantId(), kind.name(), value.id().toString(), value.ownerId(),
                value.sourceReference(), value.context(), expectedVersion);
        if (updated != 1) throw conflict();
        append(kind, value, actor, operation);
    }

    /** 不从 JSON 中推断查询租户，所有定位都使用独立身份列。 */
    public Optional<Stored> find(Kind kind, String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM finance_resource WHERE tenant_id=? AND resource_type=? AND id=?",
                (row, index) -> new Stored(UUID.fromString(row.getString("id")), row.getString("tenant_id"), row.getString("owner_id"),
                        row.getString("source_reference"), row.getLong("version"), row.getString("context_json"), row.getString("state_json")),
                tenantId, kind.name(), id.toString()).stream().findFirst();
    }

    /** 账本的预留和核销归属规范化落库，用外键阻止跨租户报销占用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceAmountUses(Kind kind, Stored value, Map<Integer, ReservedAmount> balances) {
        if (kind == Kind.INVOICE) throw new IllegalArgumentException("Invoices do not use amount reservations");
        var rows = new ArrayList<Object[]>();
        balances.forEach((line, balance) -> {
            for (var reservation : balance.reservations()) rows.add(amountRow(kind, value, line, reservation, "RESERVED"));
            for (var consumption : balance.consumptions()) rows.add(amountRow(kind, value, line, consumption, "CONSUMED"));
        });
        jdbc.update("DELETE FROM finance_amount_use WHERE tenant_id=? AND resource_type=? AND resource_id=?", value.tenantId(), kind.name(), value.id().toString());
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO finance_amount_use(tenant_id,resource_type,resource_id,source_line,report_id,round_no,report_line,amount,currency,status) VALUES(?,?,?,?,?,?,?,?,?,?)", rows);
    }

    private static Object[] amountRow(Kind kind, Stored value, int line, ReservedAmount.Reservation reservation, String status) {
        return new Object[]{value.tenantId(), kind.name(), value.id().toString(), line, reservation.use().reportId().toString(),
                reservation.use().roundNo(), reservation.use().lineNo(), reservation.amount().value(), reservation.amount().currency(), status};
    }

    private void append(Kind kind, Stored value, String actor, String operation) {
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,?,?,?,?,?,?)",
                value.tenantId(), kind.name(), value.id().toString(), value.version(), actor, operation, value.state());
    }
    private static void requireAudit(String actor, String operation) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException("INVALID_EXPENSE_AUDIT", "Financial resource changes require an actor and operation");
        }
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Financial resource version or immutable context changed"); }

    /**
     * 封闭的实际资源种类，不接受任意表名或 SQL 片段。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { INVOICE, ADVANCE, PRIOR_REQUEST }

    /**
     * 仓储内部行，不作为请求或响应公开。
     * @author owlzhangfq@gmail.com
     */
    public record Stored(UUID id, String tenantId, String ownerId, String sourceReference, long version, String context, String state) {
        /** 数据库列与恢复出的身份、背景和版本必须一致。 */
        public void requireConsistent(Stored decoded) {
            if (!id.equals(decoded.id) || !tenantId.equals(decoded.tenantId) || !ownerId.equals(decoded.ownerId)
                    || !sourceReference.equals(decoded.sourceReference) || version != decoded.version || !Objects.equals(context, decoded.context)) {
                throw new IllegalStateException("Persisted financial resource context is inconsistent");
            }
        }
    }
}
