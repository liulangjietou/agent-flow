package io.agentflow.definition;

import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.budget.BudgetAdjustmentFormContract;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestFormContract;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpensePlanFormContract;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.procurement.ProcurementPaymentFormContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 同租户发布目录与输入契约的绑定属于应用编排；领域不查询仓储，也不选择引擎最新版本。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SubprocessDefinitionResolver {
    private final DefinitionDraftRepository definitions;
    private final ProcessRuntimePort runtime;

    /** 复用现有明确来源的运行端口，禁止退回到同名内置流程或其他租户。 */
    public SubprocessDefinitionResolver(DefinitionDraftRepository definitions, ProcessRuntimePort runtime) {
        this.definitions = definitions; this.runtime = runtime;
    }

    /** 引用版本加锁至调用事务结束，与停用串行；返回自身不可变内容而非可修改定义聚合。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Bound resolve(String tenantId, SubprocessPolicy policy, String nodeId, FormSchema parentSchema) {
        var target = definitions.lockPublished(tenantId, policy.processKey(), policy.version()).orElseThrow(
                () -> new DomainException("SUBPROCESS_DEFINITION_UNAVAILABLE", "The referenced published subprocess version is unavailable"));
        target.requireStartEnabled();
        var schema = target.formSchema();
        // 与普通申请入口保持同一业务归属：映射表单不能伪造财务预检、资源占用或业务单据。
        if (ExpenseFormContract.structured(schema) || ExpensePlanFormContract.structured(schema)
                || AdvanceRequestFormContract.structured(schema) || ProcurementPaymentFormContract.structured(schema)
                || BudgetAdjustmentFormContract.structured(schema)) {
            throw new DomainException("BUSINESS_ENDPOINT_REQUIRED", "Structured financial applications require their business submission endpoint");
        }
        var inputs = SubprocessInputs.bind(policy, nodeId, parentSchema, schema);
        String runtimeDefinitionId = runtime.resolveDefinition(tenantId, target.key(), target.version(), false);
        return new Bound(target.id(), target.key(), target.version(), target.name(), runtimeDefinitionId,
                target.graph(), schema, target.notificationTexts(), inputs);
    }

    /**
     * 已解析定义只供同事务的子流程编排使用，不是客户端可提交的启动凭据。
     * @author owlzhangfq@gmail.com
     */
    public record Bound(UUID definitionId, String processKey, long version, String name, String runtimeDefinitionId,
                        DefinitionModels.Graph graph, FormSchema formSchema, NotificationTexts notificationTexts,
                        SubprocessInputs inputs) { }
}
