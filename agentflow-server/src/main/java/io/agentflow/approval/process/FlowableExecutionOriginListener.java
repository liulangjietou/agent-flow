package io.agentflow.approval.process;

import io.agentflow.approval.process.mapper.FlowableExecutionOriginListenerMapper;
import io.agentflow.observability.DiagnosticContext;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.RuntimeService;
import org.flowable.job.api.Job;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 原生任务适配器只记录创建来源；诊断数据不参与租户授权或任务状态判断。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableExecutionOriginListener implements FlowableEventListener {
    private static final Logger LOG =
            LoggerFactory.getLogger(FlowableExecutionOriginListener.class);
    private final FlowableExecutionOriginListenerMapper sqlMapper;
    private final ObjectProvider<RuntimeService> runtime;

    /** 与引擎使用同一事务数据源，禁止提交后补写来源。 */
    public FlowableExecutionOriginListener(
            FlowableExecutionOriginListenerMapper sqlMapper,
            ObjectProvider<RuntimeService> runtime) {
        this.sqlMapper = sqlMapper;
        this.runtime = runtime;
    }

    /** 创建来源只写一次；转交、暂停和原 ID 重试不改写原始来源。 */
    @Override
    public void onEvent(FlowableEvent event) {
        Object entity = ((FlowableEntityEvent) event).getEntity();
        if (entity instanceof DelegateTask task) {
            capture(task.getTenantId(), task.getProcessInstanceId(), "TASK", task.getId());
        } else if (entity instanceof Job job) {
            capture(job.getTenantId(), job.getProcessInstanceId(), "TIMER", job.getId());
        }
    }

    private void capture(String tenant, String process, String kind, String id) {
        // 全局维护任务没有业务租户或流程，不能从当前线程借用归属。
        if (tenant == null || tenant.isBlank() || process == null) return;
        // 任务可能属于当前调用的子流程；只读取它自己实例中的受控变量。
        var variables =
                runtime.getObject().getVariables(process, List.of("tenantId", "businessNo"));
        String businessNo =
                tenant.equals(variables.get("tenantId"))
                                && variables.get("businessNo") instanceof String value
                        ? value
                        : null;
        try (var scope =
                DiagnosticContext.forBusiness(
                                tenant, businessNo, process, "TASK".equals(kind) ? id : null)
                        .open()) {
            sqlMapper.capture(
                    tenant, kind, id, DiagnosticContext.capture().traceId(), tenant, kind, id);
            LOG.info(
                    "Workflow execution origin staged, errorCode={}, objectKind={}, objectId={}",
                    "NONE",
                    kind,
                    id);
        }
    }

    /** 来源登记失败与原任务一起回滚，不产生无法关联的新任务。 */
    @Override public boolean isFailOnException() { return true; }

    /** 必须在创建事务内执行。 */
    @Override public boolean isFireOnTransactionLifecycleEvent() { return false; }

    /** 不注册提交后阶段。 */
    @Override public String getOnTransaction() { return null; }

    /** 两类真实异步来源使用各自原生对象 ID，不能只按执行实例复用。 */
    @Override public Collection<? extends FlowableEventType> getTypes() {
        return List.of(FlowableEngineEventType.TASK_CREATED, FlowableEngineEventType.TIMER_SCHEDULED);
    }
}
