package io.agentflow.definition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 流程目录只读端口，摘要不加载流程图或表单正文。
 * @author owlzhangfq@gmail.com
 */
public interface DefinitionCatalogPort {
    /** 返回最多 limit + 1 项，状态可见范围已经由入口限定。 */
    List<Item> search(String tenantId, Query query);

    /**
     * 已通过入口校验的筛选与稳定创建时间游标。
     * @author owlzhangfq@gmail.com
     */
    record Query(String text, String status, String processKey, Long version, int limit,
                 Instant beforeTime, UUID beforeId) { }

    /**
     * 定义版本摘要；草稿版本为 0，完整定义需通过原详情接口重新授权读取。
     * @author owlzhangfq@gmail.com
     */
    record Item(UUID id, String key, String name, String status, long version, long revision,
                Instant createdAt, Instant updatedAt) { }
}
