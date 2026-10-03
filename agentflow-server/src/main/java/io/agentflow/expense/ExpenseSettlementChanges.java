package io.agentflow.expense;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核销、争议和退回资金用例共同使用的结算持久化与事件编排，不负责领域转换。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementChanges {
    private final JdbcExpenseSettlementRepository settlements;
    private final ApplicationEventPublisher events;
    /** 组合现有仓储与事件发布，不另建结算状态规则。 */
    public ExpenseSettlementChanges(JdbcExpenseSettlementRepository settlements, ApplicationEventPublisher events) { this.settlements = settlements; this.events = events; }
    /** 修订追加成功后才发布；消息失败与原业务事务一同回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(ExpenseSettlement previous, ExpenseSettlement current) {
        settlements.update(current); events.publishEvent(new ExpenseSettlementChanged(previous, current));
    }
}
