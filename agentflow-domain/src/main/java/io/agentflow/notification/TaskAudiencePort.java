package io.agentflow.notification;

import java.util.List;
import java.util.UUID;

/**
 * 从实际待办与有效身份源解析消息接收人，不将引擎查询对象暴露给通知用例。
 * @author owlzhangfq@gmail.com
 */
public interface TaskAudiencePort {
    /** 返回本申请当前任务及有处理资格的账号。 */
    List<Audience> pending(String tenantId, UUID applicationId);

    /** 返回尚未结束的任务接收人，包含暂停任务，供终止前保存实际通知对象。 */
    List<Audience> unfinished(String tenantId, UUID applicationId);

    /**
     * 当前任务接收人快照；候选组多人只为每个实际账号保留一次。
     * @author owlzhangfq@gmail.com
     */
    record Audience(String taskId, String nodeName, List<String> recipients, List<ProxyRecipient> proxies) {
        /** 原生接收人不隐式包含代理，代理范围由通知编排另行核对。 */
        public Audience(String taskId, String nodeName, List<String> recipients) { this(taskId, nodeName, recipients, List.of()); }
    }

    /** 状态变更前的直接代理来源，只能用于最小通知，不能作为后续读取或办理授权。
     * @author owlzhangfq@gmail.com
     */
    record ProxyRecipient(UUID proxyId, String subject) { }
}
