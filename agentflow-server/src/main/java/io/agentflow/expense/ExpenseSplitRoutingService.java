package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 费用提交的跨单编排，在既有租户配置锁内读取当前事实并冻结原路由依据。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSplitRoutingService {
    private final JdbcExpenseSplitRoutingRepository routing;

    /** 所有读写沿用费用提交事务，不增加其他报销单的行锁。 */
    public ExpenseSplitRoutingService(JdbcExpenseSplitRoutingRepository routing) { this.routing = routing; }

    /** 费用修订和派生表单已保存，原审批轮次尚未追加；后续任何失败将整笔准备回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseSplitRoutingSnapshot prepare(ExpenseReport report, Application application, DefinitionDraft definition, Instant at) {
        var configuration = ExpenseSplitRiskPolicy.from(definition.graph());
        var primary = ExpenseSplitRiskEvidence.Document.from(report, application.version(), application.status());
        ExpenseSplitRiskEvidence.Assessment assessment = null;
        if (configuration.mode() == ExpenseSplitRiskPolicy.Mode.ENABLED) {
            var scope = primary.scope();
            var categories = primary.lines().stream().filter(line -> line.approvedGross().value().signum() > 0)
                    .map(ExpenseSplitRiskEvidence.Line::categoryCode).collect(Collectors.toSet());
            var candidates = routing.candidates(scope.tenantId(), scope.employeeId(), scope.legalEntityId(), scope.currency(), report.id(),
                    at.minus(Duration.ofDays(configuration.rule().windowDays())), at, categories);
            assessment = ExpenseSplitRiskEvidence.assess(configuration.rule(), primary, candidates, at);
        }
        var snapshot = new ExpenseSplitRoutingSnapshot(ExpenseSplitRiskPolicy.RULE_VERSION, definition.id(), definition.key(), definition.version(),
                configuration, primary, assessment);
        routing.save(snapshot);
        return snapshot;
    }
}
