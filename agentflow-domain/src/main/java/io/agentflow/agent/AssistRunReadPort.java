package io.agentflow.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 单申请运行目录的只读投影，不在列表加载模型正文和证据。
 * @author owlzhangfq@gmail.com
 */
public interface AssistRunReadPort {
    /** 在已授权申请中读取一页及额外一条，所有查询必须限定租户和申请。 */
    List<Item> list(String tenantId, UUID applicationId, Query query);

    /**
     * 入口校验后的轮次和稳定游标；时间采用数据库存储精度。
     * @author owlzhangfq@gmail.com
     */
    record Query(Integer roundNo, int limit, Instant beforeTime, UUID beforeId) { }

    /**
     * 不携带申请正文、模型文本、人员或输入字段的运行摘要。
     * @author owlzhangfq@gmail.com
     */
    record Item(UUID id, long applicationVersion, int roundNo, AssistRun.Status status, long version,
                Instant createdAt) { }
}
