package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.ProcessRuntimePort.StartProcessCommand;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将服务端原轮次依据绑定到实际引擎定义；运行变量只含求值所需金额和身份，不复制跨单明细。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSplitRoutingBindings {
    public static final String VARIABLE = "agentflowExpenseSplitRouting";
    private static final String RUNTIME_DEFINITION = "runtimeDefinitionId";
    private static final String ROUTING_AMOUNT = "routingAmount";
    private static final String GATEWAYS = "gatewayIds";
    private static final String RULE_VERSION = "ruleVersion";
    private static final Set<String> VARIABLE_KEYS = Set.of("tenantId", "applicationId", "roundNo", RUNTIME_DEFINITION,
            ROUTING_AMOUNT, GATEWAYS, RULE_VERSION, "currency");
    private final JdbcExpenseSplitRoutingRepository routing;
    private final ApplicationRepository applications;
    private final ExpenseReportRepository reports;
    private final JdbcExpensePriorControlRepository priorControls;

    /** 原申请和财务修订只从可信仓储取得，不接受浏览器提交冻结依据。 */
    public ExpenseSplitRoutingBindings(JdbcExpenseSplitRoutingRepository routing, ApplicationRepository applications, ExpenseReportRepository reports,
            JdbcExpensePriorControlRepository priorControls) {
        this.routing = routing; this.applications = applications; this.reports = reports;
        this.priorControls = priorControls;
    }

    /** 已配置定义必须具有同事务准备记录；原来未配置的普通或费用定义继续使用旧引擎语义。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseSplitRoutingSnapshot require(StartProcessCommand command, DefinitionDraft definition) {
        var configuration = ExpenseSplitRiskPolicy.from(definition.graph());
        if (configuration.mode() == ExpenseSplitRiskPolicy.Mode.UNCONFIGURED) return null;
        var application = applications.findById(command.tenantId(), command.applicationId()).orElseThrow(ExpenseSplitRoutingBindings::invalid);
        var business = application.businessReference();
        if (business == null || business.type() != BusinessReference.Type.EXPENSE) throw invalid();
        var snapshot = routing.findByApplication(command.tenantId(), command.applicationId(), command.roundNo()).orElseThrow(ExpenseSplitRoutingBindings::invalid);
        var report = reports.find(command.tenantId(), business.id()).orElseThrow(ExpenseSplitRoutingBindings::invalid);
        var primary = ExpenseSplitRiskEvidence.Document.from(report, application.version(), application.status());
        if (!snapshot.reportId().equals(business.id()) || !snapshot.primary().equals(primary) || snapshot.roundNo() != command.roundNo()
                || !snapshot.definitionId().equals(definition.id()) || snapshot.definitionVersion() != definition.version()
                || !snapshot.processKey().equals(definition.key()) || !snapshot.configuration().equals(configuration)
                || !definition.tenantId().equals(command.tenantId()) || !definition.key().equals(command.processKey())
                || definition.version() != command.definitionVersion()
                || !ExpenseFormContract.submittedPayload(report.currentRound(), priorControls.routingFlag(report, definition.formSchema())).equals(command.payload())) throw invalid();
        return snapshot;
    }

    /** 只把不可编辑的本轮求值信息交给引擎；原始来源保留在权限受控的费用仓储。 */
    public static Map<String, Object> variables(ExpenseSplitRoutingSnapshot snapshot, String runtimeDefinitionId) {
        if (snapshot == null || snapshot.configuration().mode() != ExpenseSplitRiskPolicy.Mode.ENABLED) return Map.of();
        return Map.of("tenantId", snapshot.tenantId(), "applicationId", snapshot.applicationId().toString(), "roundNo", snapshot.roundNo(),
                RUNTIME_DEFINITION, runtimeDefinitionId, RULE_VERSION, snapshot.ruleVersion(), GATEWAYS, snapshot.configuration().gatewayIds(),
                ROUTING_AMOUNT, snapshot.assessment().routingAmount().value(), "currency", snapshot.primary().scope().currency());
    }

    /** 标记网关只认当前实例、申请和轮次；缺少、串用或错误类型的变量不能降级为本单金额。 */
    public static BigDecimal routingAmount(DelegateExecution execution) {
        if (execution == null || !(execution.getVariable(VARIABLE) instanceof Map<?, ?> value) || !VARIABLE_KEYS.equals(value.keySet())
                || !Integer.valueOf(ExpenseSplitRiskPolicy.RULE_VERSION).equals(value.get(RULE_VERSION))
                || !execution.getProcessDefinitionId().equals(value.get(RUNTIME_DEFINITION))
                || !java.util.Objects.equals(execution.getVariable("tenantId"), value.get("tenantId"))
                || !java.util.Objects.equals(execution.getVariable("applicationId"), value.get("applicationId"))
                || !java.util.Objects.equals(execution.getVariable("roundNo"), value.get("roundNo"))
                || !(value.get(GATEWAYS) instanceof Set<?> gateways) || !gateways.contains(execution.getCurrentActivityId())
                || !(value.get(ROUTING_AMOUNT) instanceof BigDecimal amount) || amount.signum() < 0
                || !(execution.getVariable("formData") instanceof Map<?, ?> form)
                || !java.util.Objects.equals(value.get("currency"), form.get(ExpenseFormContract.CURRENCY))) throw invalid();
        return amount;
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_SPLIT_CONTEXT_INVALID", "Split routing requires the original prepared report, rule and runtime binding");
    }
}
