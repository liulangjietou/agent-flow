package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
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
 * 部分调整公开决定和对应持久变化同事务审计，不复制账户或原财务命令。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpensePartialAdjustmentAudit {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final CurrentActor actors;
    /** 操作身份始终取认证上下文，不能由页面指定。 */
    public ExpensePartialAdjustmentAudit(JdbcTemplate jdbc, JsonUtil json, CurrentActor actors) { this.jdbc = jdbc; this.json = json; this.actors = actors; }

    /** 原件查询以原报销定位，创建及后续决定以真实调整或准备版本定位。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(ExpenseReport report, UUID id, long version, Action action, String comment, Instant at) {
        var event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'ExpensePartialAdjustment',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), report.tenantId(), event.toString(), id.toString(), version, report.applicationId().toString(),
                "EXPENSE_PARTIAL_" + action.name(), actors.actor().userId(), json.write(Map.of("reportId", report.id(), "roundNo", report.requireFrozenRound().roundNo(),
                        "authorizedRole", "FINANCE", "comment", comment)), Timestamp.from(at));
        return event;
    }

    /**
     * 明确区分只读刷新、固定意图和实际写入授权。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { ORIGINAL_QUERY, CREATE, PREPARE, AUTHORIZE }

    /**
     * 幂等回执只携带已保存的定位，不携带敏感原件或可直接外发的命令。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(UUID reportId, int roundNo, UUID adjustmentId, Long adjustmentVersion, UUID preparationId, Long preparationVersion, UUID auditEventId) { }
}
