package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 仅允许六种已知运行的观测，SQL 不接收任意表名或人员条件。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AgentUsageMapper {
    /** 读取原持久运行时间和业务关联。 */
    List<SqlRow> source(@Param("tenant") String tenant, @Param("kind") String kind, @Param("runId") String runId);
    /** 在模型执行前建立唯一观测。 */
    int insert(@Param("tenant") String tenant, @Param("owner") String owner, @Param("runId") String runId,
            @Param("kind") String kind, @Param("subject") String subject, @Param("state") String state);
    /** 不追加第二次调用，只补齐同一运行的结果。 */
    int finish(@Param("tenant") String tenant, @Param("runId") String runId, @Param("kind") String kind, @Param("state") String state);
    /** 本人索引限定最近一百项。 */
    List<SqlRow> list(@Param("tenant") String tenant, @Param("owner") String owner, @Param("subject") String subject);
    /** 按租户统计指定起始时刻以来的用量明细，用于日度 Token 配额管控。 */
    List<SqlRow> listTenantSince(@Param("tenant") String tenant, @Param("since") java.time.OffsetDateTime since);
}
