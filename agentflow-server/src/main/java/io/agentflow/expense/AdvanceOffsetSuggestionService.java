package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 将有效预检金额与本人实际借款编排为可调整的建议，读取不预留资金或改写草稿。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceOffsetSuggestionService {
    private static final int PAGE_SIZE = 100;
    private final CurrentActor actors;
    private final ExpensePrecheckService prechecks;
    private final ExpenseReportRepository reports;
    private final EmployeeAdvanceRepository advances;

    /** 复用预检的本人授权和有效性规则，借款可用额度由实体自身判断。 */
    public AdvanceOffsetSuggestionService(CurrentActor actors, ExpensePrecheckService prechecks, ExpenseReportRepository reports, EmployeeAdvanceRepository advances) {
        this.actors = actors; this.prechecks = prechecks; this.reports = reports; this.advances = advances;
    }

    /** 授权校验沿用组织目录锁，同一快照内跨页只查询资金；不能声明禁止加锁的只读数据库事务。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Suggestion suggest(UUID reportId, UUID precheckId) {
        var checked = prechecks.get(reportId, precheckId);
        if (!checked.usable()) throw new DomainException(checked.unavailableCode(), "Refresh the expense precheck before requesting an offset suggestion");
        var report = reports.find(actors.actor().tenantId(), reportId).orElseThrow();
        ExpenseUse retained = report.rounds().isEmpty() ? null : new ExpenseUse(report.id(), report.currentRound().roundNo(), 0);
        var gross = checked.preview().approvedGross(); var remaining = gross;
        var items = new ArrayList<Item>(); EmployeeAdvanceRepository.PaidCursor cursor = null;
        while (remaining.value().signum() > 0 && items.size() < ExpenseContent.MAX_ADVANCES) {
            var page = advances.findOwnedByPaidOn(report.tenantId(), report.employeeId(), report.content().legalEntityId(), gross.currency(), cursor, PAGE_SIZE);
            for (var advance : page) {
                var capacity = advance.offsetCapacity(retained); var amount = remaining.min(capacity);
                if (amount.value().signum() > 0) {
                    items.add(new Item(advance.id(), advance.version(), advance.paidOn(), capacity, amount)); remaining = remaining.minus(amount);
                }
                if (remaining.value().signum() == 0 || items.size() == ExpenseContent.MAX_ADVANCES) break;
            }
            if (page.size() < PAGE_SIZE) break;
            var last = page.get(page.size() - 1); cursor = new EmployeeAdvanceRepository.PaidCursor(last.paidOn(), last.id());
        }
        if (!checked.validUntil().isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Expense precheck expired while calculating offsets");
        return new Suggestion(report.id(), report.applicationId(), checked.job().applicationVersion(), checked.job().financialVersion(), precheckId,
                checked.validUntil(), gross, gross.minus(remaining), remaining, items.size() == ExpenseContent.MAX_ADVANCES && remaining.value().signum() > 0, List.copyOf(items));
    }

    /**
     * 建议固定预检和双版本，最多与一份草稿可接受的借款数量相同。
     * @author owlzhangfq@gmail.com
     */
    public record Suggestion(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, UUID precheckId,
            Instant validUntil, Money approvedGross, Money offsetTotal, Money payable, boolean selectionLimitReached, List<Item> items) { }

    /**
     * 可建议额度包含本单保留预留，不包含其他单占用；不公开付款账户和银行参考号。
     * @author owlzhangfq@gmail.com
     */
    public record Item(UUID advanceId, long version, LocalDate paidOn, Money capacity, Money amount) { }
}
