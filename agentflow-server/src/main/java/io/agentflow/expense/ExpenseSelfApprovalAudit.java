package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.common.JsonUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上溯是选人事实，随节点候选冻结追加审计，不冒充人工批准或改变申请结论。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpenseSelfApprovalAudit {
    public static final String ACTION = "SELF_APPROVAL_ESCALATED";
    private static final String ACTOR = "system:expense-self-approval";
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 原申请的历史入口复用同一审计事件存储。 */
    public ExpenseSelfApprovalAudit(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 节点重复读取候选时由调用方复用快照，不重复追加本次选人事件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Application application, ExpenseSelfApprovalSnapshot snapshot, String nodeId,
            String processInstanceId, java.util.List<String> effectiveCandidates) {
        var selection = snapshot.node(nodeId); var escalation = selection.escalation();
        if (escalation == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", ACTION); payload.put("actor", ACTOR); payload.put("applicationId", application.id().toString());
        payload.put("roundNo", snapshot.roundNo()); payload.put("processInstanceId", processInstanceId);
        payload.put("nodeId", nodeId); payload.put("nodeName", selection.nodeName());
        payload.put("definitionId", snapshot.definitionId()); payload.put("definitionVersion", snapshot.definitionVersion());
        payload.put("ruleVersion", snapshot.ruleVersion()); payload.put("initiator", snapshot.initiator());
        payload.put("selection", selection); payload.put("effectiveCandidates", effectiveCandidates);
        payload.put("targetUser", escalation.replacementSubject());
        payload.put("comment", "申请人自审批已按本次任职上溯直属主管：" + escalation.originalSubject() + " → "
                + escalation.replacementSubject() + "；发布版本 " + snapshot.definitionVersion() + "，规则版本 " + snapshot.ruleVersion());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'Application',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), application.tenantId(), UUID.randomUUID().toString(), application.id().toString(),
                application.version(), application.id().toString(), ACTION, ACTOR, json.write(payload), Timestamp.from(Instant.now()));
    }
}
