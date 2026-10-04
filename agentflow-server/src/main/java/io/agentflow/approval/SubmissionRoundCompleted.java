package io.agentflow.approval;

import io.agentflow.approval.model.SubmissionRound;
import java.util.UUID;

/**
 * 轮次结论成功保存后的事务内事件；业务监听者重新读取该轮次的持久事实。
 * @author owlzhangfq@gmail.com
 */
public record SubmissionRoundCompleted(String tenantId, UUID applicationId, int roundNo, SubmissionRound.Status status) { }
