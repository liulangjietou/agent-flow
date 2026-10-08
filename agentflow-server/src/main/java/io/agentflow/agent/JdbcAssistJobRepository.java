package io.agentflow.agent;


import com.fasterxml.jackson.core.type.TypeReference;

import io.agentflow.agent.mapper.AssistJobRepositoryMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久执行输入与租约，不改写已有来源，也不为历史运行补造发送授权。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAssistJobRepository {
    private final AssistJobRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 与运行及状态轨迹共用原数据源事务。 */
    public JdbcAssistJobRepository(AssistJobRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 输入与运行在同一创建事务中冻结。 */
    public void create(Job job) {
        sqlMapper.create(
                job.tenantId(),
                job.runId().toString(),
                job.taskId(),
                json.write(job.requester()),
                json.write(job.sources()),
                job.targetDigest(),
                DiagnosticContext.capture().traceId());
    }

    /** 原文仅供已授权的应用服务读取，不直接返回 HTTP。 */
    public Optional<Job> find(String tenant, UUID runId) {
        return SqlRows.map(
                        sqlMapper.find(tenant, runId.toString()),
                        row -> {
                            Timestamp lease = row.getTimestamp("lease_until");
                            return new Job(
                                    row.getString("tenant_id"),
                                    UUID.fromString(row.getString("run_id")),
                                    row.getString("task_id"),
                                    json.read(row.getString("requester_json"), Actor.class),
                                    json.read(
                                            row.getString("sources_json"),
                                            new TypeReference<List<AssistModelPort.Source>>() {}),
                                    row.getString("target_digest"),
                                    lease == null ? null : lease.toInstant());
                        })
                .stream()
                .findFirst();
    }

    /** 有界扫描待执行与租约过期记录；普通只读历史不进入执行队列。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(Timestamp.from(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("run_id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id"),
                                row.getString("task_id")));
    }

    /** 单个运行只有一个领取/结算者；必须在调用方事务内使用。 */
    public boolean lock(String tenant, UUID runId) {
        return !sqlMapper.lock(tenant, runId.toString()).isEmpty();
    }

    /** 锁后写入或清除租约；终态原始输入继续保留用于人工核对。 */
    public void lease(String tenant, UUID runId, Instant until) {
        sqlMapper.lease(until == null ? null : Timestamp.from(until), tenant, runId.toString());
    }

    /**
     * 任务上下文由服务端认证和冻结来源构造，不从请求接收角色、地址或正文。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Job(
            String tenantId,
            UUID runId,
            String taskId,
            Actor requester,
            List<AssistModelPort.Source> sources,
            String targetDigest,
            Instant leaseUntil) {}

    /**
     * 扫描结果不包含业务正文或模型凭据。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID runId,
            String traceId,
            String businessNo,
            String processInstanceId,
            String taskId) {
        /** 没有业务关联的历史调用保持缺失事实，不借用当前线程。 */
        public Candidate(String tenantId, UUID runId, String traceId) { this(tenantId, runId, traceId, null, null, null); }
    }
}
