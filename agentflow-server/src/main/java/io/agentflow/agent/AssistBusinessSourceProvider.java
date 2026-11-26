package io.agentflow.agent;

import io.agentflow.approval.model.Application;
import java.util.List;

/**
 * 为结构化业务单据提供经脱敏与权限裁剪后的只读摘要输入源，供审批摘要助手按轮次显式勾选。
 * @author owlzhangfq@gmail.com
 */
public interface AssistBusinessSourceProvider {
    /** 返回指定提交轮次允许外发给模型的非敏感业务事实；无匹配业务时返回空列表。 */
    List<Projection> sources(Application application, int roundNo);

    /**
     * 单个可勾选的业务摘要投影项，不包含收款账号原文或内部凭据。
     * @author owlzhangfq@gmail.com
     */
    record Projection(String sourceId, String label, Object value) { }
}
