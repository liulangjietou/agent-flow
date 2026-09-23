package io.agentflow.template;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 模板复制出处端口，读取时联合当前定义状态，所有操作都携带租户。
 * @author owlzhangfq@gmail.com
 */
public interface TemplateCopyRepository {
    /** 保存复制时的来源事实。 */
    void save(TemplateCopy copy);

    /** 只查询指定租户和模板的副本。 */
    List<CopyView> findByTemplate(String tenantId, String templateKey);

    /**
     * 副本视图保留原始来源版本，并展示当前定义名称及发布状态。
     * @author owlzhangfq@gmail.com
     */
    record CopyView(UUID definitionId, String processKey, String name, String status, long version, long revision,
                    long templateVersion, String copiedBy, Instant copiedAt) { }
}
