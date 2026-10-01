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
    record Audience(String taskId, String nodeName, List<String> recipients) { }
}
