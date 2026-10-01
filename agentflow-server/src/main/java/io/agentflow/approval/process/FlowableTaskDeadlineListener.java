package io.agentflow.approval.process;

import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.BusinessDeadline;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.TaskDeadlinePolicy;
import io.agentflow.definition.TaskEscalationPolicy;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.RepositoryService;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 引擎创建任务时绑定实际发布版本的期限；与任务创建同事务，不依赖页面或异步补算。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableTaskDeadlineListener implements FlowableEventListener {
    public static final String CALENDAR_ID = "agentflowDeadlineCalendarId";
    public static final String CALENDAR_REVISION = "agentflowDeadlineCalendarRevision";
    public static final String WORKING_MINUTES = "agentflowDeadlineWorkingMinutes";
    public static final String STARTED_AT = "agentflowDeadlineStartedAt";
    public static final String REMINDED_AT = "agentflowDeadlineRemindedAt";
    private final DefinitionDraftRepository definitions;
    private final BusinessCalendarRepository calendars;
    private final ObjectProvider<RepositoryService> engine;
    private final TaskEscalationBindings escalations;

    /** 引擎服务延迟获取，配置监听器时不反向初始化引擎。 */
    public FlowableTaskDeadlineListener(DefinitionDraftRepository definitions, BusinessCalendarRepository calendars,
                                       ObjectProvider<RepositoryService> engine, TaskEscalationBindings escalations) {
        this.definitions = definitions;
        this.calendars = calendars;
        this.engine = engine;
        this.escalations = escalations;
    }

    /** 每张新任务独立起算，会签和并行分支也保留各自的创建时刻。 */
    @Override
    public void onEvent(FlowableEvent event) {
        var task = (DelegateTask) ((FlowableEngineEntityEvent) event).getEntity();
        if (task.getProcessDefinitionId() == null) return;
        String tenantId = (String) task.getVariable("tenantId");
        var bound = engine.getObject().getProcessDefinition(task.getProcessDefinitionId());
        // 全局内置定义不借用同名租户定义的期限，非平台任务不进入平台配置查找。
        if (tenantId == null || !tenantId.equals(bound.getTenantId())) return;
        var definition = definitions.findPublished(tenantId, bound.getKey(), bound.getVersion());
        if (definition.isEmpty()) return;
        definition.get().graph().nodes().stream().filter(node -> node.id().equals(task.getTaskDefinitionKey()))
                .findFirst().ifPresent(node -> TaskDeadlinePolicy.fromProperties(node.properties()).ifPresent(policy -> {
                    var calendar = calendars.findVersion(tenantId, policy.calendarId(), policy.calendarRevision())
                            .orElseThrow(() -> new DomainException("DEADLINE_CALENDAR_UNAVAILABLE", "Bound deadline calendar is unavailable"));
                    var deadline = BusinessDeadline.calculate(calendar.rules(), task.getCreateTime().toInstant(), policy.workingMinutes());
                    task.setDueDate(Date.from(deadline.dueAt()));
                    task.setVariableLocal(CALENDAR_ID, policy.calendarId().toString());
                    task.setVariableLocal(CALENDAR_REVISION, policy.calendarRevision());
                    task.setVariableLocal(WORKING_MINUTES, policy.workingMinutes());
                    task.setVariableLocal(STARTED_AT, deadline.startAt().toString());
                    TaskEscalationPolicy.fromProperties(node.properties()).ifPresent(escalation ->
                            escalations.bind(task, tenantId, escalation, calendar.rules(), deadline.dueAt()));
                }));
    }

    /** 计算失败须回滚业务动作，不能静默创建缺少已配置期限的任务。 */
    @Override
    public boolean isFailOnException() { return true; }

    /** 创建事务内执行，禁止提交后再补写。 */
    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return false; }

    /** 无提交后回调阶段。 */
    @Override
    public String getOnTransaction() { return null; }

    /** 指派、委派和回交不重新计算期限。 */
    @Override
    public Collection<? extends FlowableEventType> getTypes() { return List.of(FlowableEngineEventType.TASK_CREATED); }
}
