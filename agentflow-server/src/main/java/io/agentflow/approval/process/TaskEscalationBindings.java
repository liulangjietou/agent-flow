package io.agentflow.approval.process;

import io.agentflow.calendar.BusinessDeadline;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.TaskEscalationPolicy;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.organization.OrganizationAssigneeResolver;
import java.time.Instant;
import java.util.Date;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;

/** 新任务在原事务固定升级时间、选人规则、目录修订和实际名单。
 * @author owlzhangfq@gmail.com
 */
@Component
public class TaskEscalationBindings {
    public static final String DUE_AT = "agentflowEscalationDueAt";
    public static final String WORKING_MINUTES = "agentflowEscalationWorkingMinutes";
    public static final String AUDIENCE = "agentflowEscalationAudience";
    public static final String ESCALATED_AT = "agentflowEscalatedAt";
    public static final String NOTIFIED_RECIPIENTS = "agentflowEscalationNotifiedRecipients";
    public static final String PAUSED_DUE_AT = "agentflowEscalationPauseDueAt";
    private final OrganizationAssigneeResolver recipients;
    private final JsonUtil json;

    /** 组织与日历属于应用编排，期限状态仍以原生任务为准。 */
    public TaskEscalationBindings(OrganizationAssigneeResolver recipients, JsonUtil json) {
        this.recipients = recipients; this.json = json;
    }

    /** 从原审批期限追加工作时长；收件人解析失败时回滚任务创建，不能留下空规则。 */
    public void bind(DelegateTask task, String tenant, TaskEscalationPolicy policy, CalendarRules calendar, Instant dueAt) {
        var raw = task.getVariable(FlowableProcessRuntimeAdapter.INITIATOR_CONTEXT);
        var context = raw instanceof String value ? json.read(value, InitiatorContext.class) : null;
        var audience = recipients.resolveEscalation(tenant, policy.recipientRule(), context);
        task.setVariableLocal(DUE_AT, Date.from(BusinessDeadline.calculate(calendar, dueAt, policy.workingMinutes()).dueAt()));
        task.setVariableLocal(WORKING_MINUTES, policy.workingMinutes());
        task.setVariableLocal(AUDIENCE, json.write(audience));
    }
}
