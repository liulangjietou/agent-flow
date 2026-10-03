package io.agentflow.finance;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 财务与出纳的人工决定共同写入付款审计，只记录原绑定、角色、动作和说明，不复制账户或金额。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentAudit {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 审计加入原动作事务，失败时不能留下无审计的授权或执行请求。 */
    public PaymentAudit(CurrentActor actors, JdbcTemplate jdbc, JsonUtil json) { this.actors = actors; this.jdbc = jdbc; this.json = json; }
    /** 角色已在对应业务入口验证，记录当前认证主体，不从请求正文接收操作者。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(PaymentAuthorization authorization, UUID aggregateId, long version, String role, String action, String previous, String current, String comment, Instant now) {
        var actor = actors.actor(); var binding = authorization.terms().binding(); var payload = new LinkedHashMap<String, Object>(); var event = UUID.randomUUID();
        payload.put("authorizationId", authorization.terms().id()); payload.put("roundNo", binding.roundNo()); payload.put("authorizedRole", role);
        payload.put("applicationVersion", binding.applicationVersion()); payload.put("businessVersion", binding.businessVersion());
        payload.put("previousStatus", previous); payload.put("currentStatus", current); payload.put("comment", comment.trim());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'Payment',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), actor.tenantId(), event.toString(), aggregateId.toString(), version, binding.applicationId().toString(), action, actor.userId(), json.write(payload), Timestamp.from(now));
        return event;
    }
}
