package io.agentflow.expense;

import io.agentflow.approval.SubmissionRoundCompleted;
import io.agentflow.approval.model.SubmissionRound;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 各类真实退回和撤回共用轮次事件；没有显式规则的租户维持原预算保留方式。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExpenseBudgetRetentionListener {
    private final ExpenseBudgetRetentionConfiguration configuration;
    private final ExpenseBudgetRetentionService service;

    /** 过滤通用审批事件后，再进入要求原事务的费用服务。 */
    public ExpenseBudgetRetentionListener(ExpenseBudgetRetentionConfiguration configuration, ExpenseBudgetRetentionService service) {
        this.configuration = configuration; this.service = service;
    }

    /** 同步调用保证登记失败时轮次结论也回滚，不采用提交后可能丢失的异步事件。 */
    @EventListener
    public void completed(SubmissionRoundCompleted event) {
        if (event.status() != SubmissionRound.Status.RETURNED && event.status() != SubmissionRound.Status.WITHDRAWN) return;
        var policy = configuration.policy(event.tenantId());
        if (policy != null) service.retain(event, policy);
    }
}
