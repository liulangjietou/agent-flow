package io.agentflow.expense;

import io.agentflow.common.JsonUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 额度关闭原因与财务资源版本同事务留存，不改写原申请审批结论，也不复制余额明细。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpenseRequestClosureAudit {
    public static final String ACTION = "EXPENSE_REQUEST_CLOSED";
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 使用现有审计表和资源写事务，不增加一份可独立漂移的关闭状态。 */
    public ExpenseRequestClosureAudit(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 记录当前申请人的明确原因和相邻版本，失败必须回滚关闭及幂等回执。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(ExpenseRequest request, String actor, long previousVersion, String comment, Instant at) {
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'ExpenseRequest',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), request.tenantId(), event.toString(), request.id().toString(), request.version(),
                request.applicationId().toString(), ACTION, actor, json.write(Map.of("previousVersion", previousVersion, "comment", comment)), Timestamp.from(at));
        return event;
    }
}
