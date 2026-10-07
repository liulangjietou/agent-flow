package io.agentflow.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久执行输入与租约，不改写已有来源，也不为历史运行补造发送授权。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAssistJobRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与运行及状态轨迹共用原数据源事务。 */
    public JdbcAssistJobRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 输入与运行在同一创建事务中冻结。 */
    public void create(Job job) {
        jdbc.update("INSERT INTO agent_assist_job(tenant_id,run_id,task_id,requester_json,sources_json,target_digest,trace_id) VALUES(?,?,?,?,?,?,?)",
                job.tenantId(), job.runId().toString(), job.taskId(), json.write(job.requester()), json.write(job.sources()), job.targetDigest(), DiagnosticContext.capture().traceId());
    }

    /** 原文仅供已授权的应用服务读取，不直接返回 HTTP。 */
    public Optional<Job> find(String tenant, UUID runId) {
        return jdbc.query("SELECT * FROM agent_assist_job WHERE tenant_id=? AND run_id=?", (row, index) -> {
            Timestamp lease = row.getTimestamp("lease_until");
            return new Job(row.getString("tenant_id"), UUID.fromString(row.getString("run_id")), row.getString("task_id"),
                    json.read(row.getString("requester_json"), Actor.class),
                    json.read(row.getString("sources_json"), new TypeReference<List<AssistModelPort.Source>>() { }),
                    row.getString("target_digest"), lease == null ? null : lease.toInstant());
        }, tenant, runId.toString()).stream().findFirst();
    }

    /** 有界扫描待执行与租约过期记录；普通只读历史不进入执行队列。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT j.tenant_id,j.run_id,j.trace_id FROM agent_assist_job j
                JOIN agent_assist_run r ON r.tenant_id=j.tenant_id AND r.id=j.run_id
                WHERE r.status='QUEUED' OR (r.status='RUNNING' AND j.lease_until<=?)
                ORDER BY r.created_at,j.run_id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("run_id")), row.getString("trace_id")), Timestamp.from(now));
    }

    /** 单个运行只有一个领取/结算者；必须在调用方事务内使用。 */
    public boolean lock(String tenant, UUID runId) {
        return !jdbc.queryForList("SELECT run_id FROM agent_assist_job WHERE tenant_id=? AND run_id=? FOR UPDATE",
                String.class, tenant, runId.toString()).isEmpty();
    }

    /** 锁后写入或清除租约；终态原始输入继续保留用于人工核对。 */
    public void lease(String tenant, UUID runId, Instant until) {
        jdbc.update("UPDATE agent_assist_job SET lease_until=? WHERE tenant_id=? AND run_id=?",
                until == null ? null : Timestamp.from(until), tenant, runId.toString());
    }

    /**
     * 任务上下文由服务端认证和冻结来源构造，不从请求接收角色、地址或正文。
     * @author owlzhangfq@gmail.com
     */
    public record Job(String tenantId, UUID runId, String taskId, Actor requester, List<AssistModelPort.Source> sources,
                      String targetDigest, Instant leaseUntil) { }

    /**
     * 扫描结果不包含业务正文或模型凭据。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID runId, String traceId) { }
}
