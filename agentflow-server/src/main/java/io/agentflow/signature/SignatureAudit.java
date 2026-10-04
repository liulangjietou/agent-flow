package io.agentflow.signature;

import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

/**
 * 签署轨迹加入原业务事务，后台动作与具名授权人分别记录，不复制原文和认证引用。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SignatureAudit {
    public static final String WORKER = "signature-worker";
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 只记录固定操作身份、轮次和状态，不把回执或服务方账户写入通用审计。 */
    public SignatureAudit(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 审计失败回滚授权或进度，不能留下无法追溯的已发送领取。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(SignatureOperation operation, Action action, String actor) {
        var request = operation.input().request();
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'Signature',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), request.tenantId(), UUID.randomUUID().toString(), request.id().toString(), operation.version(),
                request.source().applicationId().toString(), action.name(), actor,
                json.write(Map.of("roundNo", request.source().roundNo(), "authorizedBy", request.authorization().actor(), "status", operation.status().name())),
                Timestamp.from(operation.updatedAt()));
    }
    /**
     * 撤销登录与用户主动取消各有依据，不把本地取消伪装成远端撤销成功。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { SIGNATURE_AUTHORIZED, SIGNATURE_CLAIMED, SIGNATURE_AUTHORIZATION_REVOKED, SIGNATURE_CANCELLED,
        SIGNATURE_EXPIRED, SIGNATURE_LEASE_EXPIRED, SIGNATURE_OBSERVED, SIGNATURE_RETRY, SIGNATURE_SAVED }
}
