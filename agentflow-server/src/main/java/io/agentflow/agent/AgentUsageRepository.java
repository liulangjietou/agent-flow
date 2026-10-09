package io.agentflow.agent;

import io.agentflow.agent.mapper.AgentUsageMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型外发前先保存运行身份，完成后单独提交观测，不能把网络请求包含在数据库事务中。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class AgentUsageRepository {
    private final AgentUsageMapper mapper;
    private final JsonUtil json;

    /** 原模型来源及认证凭据不进入用量表。 */
    public AgentUsageRepository(AgentUsageMapper mapper, JsonUtil json) { this.mapper = mapper; this.json = json; }

    /** 单次领取只能建立一个观测；崩溃留下 IN_PROGRESS，不能重放模型来补齐。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentExecutionUsage begin(String tenant, String user, AgentExecutionUsage.Kind kind, UUID runId, Instant startedAt) {
        var source = SqlRows.single(mapper.source(tenant, kind.name(), runId.toString()));
        Instant queued = source.getTimestamp("created_at").toInstant();
        var value = new AgentExecutionUsage(runId, kind, UUID.fromString(source.getString("subject_id")), queued, startedAt,
                null, Math.max(0, Duration.between(queued, startedAt).toMillis()), null, "IN_PROGRESS", null, null, null,
                "NOT_REPORTED", null, null, null);
        mapper.insert(tenant, user, runId.toString(), kind.name(), value.subjectId().toString(), json.write(value));
        return value;
    }

    /** 外部结果与业务格式校验结果一起保留；没有价格版本时不生成成本估算。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String tenant, AgentExecutionUsage value) {
        if (mapper.finish(tenant, value.runId().toString(), value.kind().name(), json.write(value)) != 1) {
            throw new IllegalStateException("Agent usage observation was not updated");
        }
    }

    /** 只读本人最近 100 次执行，费用或申请标识只是进一步收窄，不能扩大访问范围。 */
    @Transactional(readOnly = true)
    public List<AgentExecutionUsage> list(String tenant, String user, UUID subjectId) {
        return SqlRows.map(mapper.list(tenant, user, subjectId == null ? null : subjectId.toString()),
                row -> json.read(row.getString("state_json"), AgentExecutionUsage.class));
    }
}
