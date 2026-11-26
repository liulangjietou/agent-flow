package io.agentflow.agent;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.budget.BudgetAdjustmentRepository;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpensePlanRepository;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.procurement.ProcurementPaymentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将五类结构化财务单据在指定提交轮次冻结的非敏感事实投影为可勾选摘要源，
 * 避免审批摘要助手仅能看到顶层金额与标题；严禁投影银行账号原文、税号明文或外部凭据。
 * @author owlzhangfq@gmail.com
 */
@Component
public class StructuredFinancialAssistSources implements AssistBusinessSourceProvider {
    private final ExpenseReportRepository expenses;
    private final ExpensePlanRepository plans;
    private final AdvanceRequestRepository advances;
    private final ProcurementPaymentRepository procurements;
    private final BudgetAdjustmentRepository budgetAdjustments;

    /** 复用五类结构化财务领域仓储，按冻结轮次只读查询。 */
    public StructuredFinancialAssistSources(ExpenseReportRepository expenses, ExpensePlanRepository plans,
                                            AdvanceRequestRepository advances, ProcurementPaymentRepository procurements,
                                            BudgetAdjustmentRepository budgetAdjustments) {
        this.expenses = expenses;
        this.plans = plans;
        this.advances = advances;
        this.procurements = procurements;
        this.budgetAdjustments = budgetAdjustments;
    }

    @Override
    public List<Projection> sources(Application application, int roundNo) {
        BusinessReference ref = application.businessReference();
        if (ref == null) return List.of();
        return switch (ref.type()) {
            case EXPENSE -> expenseSources(application.tenantId(), ref.id(), roundNo);
            case EXPENSE_PLAN -> planSources(application.tenantId(), ref.id(), roundNo);
            case ADVANCE_REQUEST -> advanceSources(application.tenantId(), ref.id(), roundNo);
            case PROCUREMENT_PAYMENT -> procurementSources(application.tenantId(), ref.id(), roundNo);
            case BUDGET_ADJUSTMENT -> budgetAdjustmentSources(application.tenantId(), ref.id(), roundNo);
        };
    }

    private List<Projection> expenseSources(String tenantId, java.util.UUID id, int roundNo) {
        var report = expenses.find(tenantId, id).orElse(null);
        if (report == null) return List.of();
        var round = report.rounds().stream().filter(item -> item.roundNo() == roundNo).findFirst().orElse(null);
        if (round == null) return List.of();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("type", round.content().type().name());
        summary.put("baseCurrency", round.baseCurrency());
        summary.put("approvedGross", round.approvedGross().value().toPlainString());
        summary.put("approvedTax", round.approvedTax().value().toPlainString());
        summary.put("offsetTotal", round.offsetTotal().value().toPlainString());
        summary.put("maskedAccount", round.account().maskedAccount());
        var lines = new ArrayList<Map<String, Object>>();
        for (var line : round.content().lines()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("lineNo", line.lineNo());
            item.put("categoryCode", line.categoryCode());
            item.put("cityCode", line.cityCode());
            item.put("incurredOn", line.incurredOn().toString());
            item.put("description", line.description());
            item.put("claimedGross", line.claimedGross().value().toPlainString());
            item.put("currency", line.claimedGross().currency());
            lines.add(item);
        }
        return List.of(
                new Projection("business:expense:summary", "报销财务汇总", summary),
                new Projection("business:expense:lines", "报销费用明细", lines));
    }

    private List<Projection> planSources(String tenantId, java.util.UUID id, int roundNo) {
        var plan = plans.find(tenantId, id).orElse(null);
        if (plan == null) return List.of();
        var round = plan.rounds().stream().filter(item -> item.roundNo() == roundNo).findFirst().orElse(null);
        if (round == null) return List.of();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("legalEntity", round.legalEntity().name());
        summary.put("baseCurrency", round.legalEntity().baseCurrency());
        summary.put("totalAmount", round.total().value().toPlainString());
        var lines = new ArrayList<Map<String, Object>>();
        for (var frozen : round.lines()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("categoryCode", frozen.original().categoryCode());
            item.put("description", frozen.original().description());
            item.put("amount", frozen.amount().value().toPlainString());
            item.put("currency", frozen.amount().currency());
            lines.add(item);
        }
        return List.of(
                new Projection("business:expense-plan:summary", "事前申请汇总", summary),
                new Projection("business:expense-plan:lines", "事前计划明细", lines));
    }

    private List<Projection> advanceSources(String tenantId, java.util.UUID id, int roundNo) {
        var request = advances.find(tenantId, id).orElse(null);
        if (request == null) return List.of();
        var round = request.rounds().stream().filter(item -> item.roundNo() == roundNo).findFirst().orElse(null);
        if (round == null) return List.of();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("legalEntity", round.legalEntity().name());
        summary.put("purpose", round.content().purpose());
        summary.put("amount", round.content().amount().value().toPlainString());
        summary.put("currency", round.content().amount().currency());
        summary.put("dueOn", round.content().dueOn().toString());
        summary.put("maskedAccount", round.account().maskedAccount());
        return List.of(new Projection("business:advance-request:summary", "员工借款要素", summary));
    }

    private List<Projection> procurementSources(String tenantId, java.util.UUID id, int roundNo) {
        var request = procurements.find(tenantId, id).orElse(null);
        if (request == null) return List.of();
        var round = request.rounds().stream().filter(item -> item.roundNo() == roundNo).findFirst().orElse(null);
        if (round == null) return List.of();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("legalEntity", round.legalEntity().name());
        summary.put("purpose", round.content().purpose());
        summary.put("supplierReference", round.content().supplierReference());
        summary.put("payableReference", round.content().payableReference());
        summary.put("requestedAmount", round.content().amount().value().toPlainString());
        summary.put("payableOutstanding", round.payable().outstanding().value().toPlainString());
        summary.put("currency", round.legalEntity().baseCurrency());
        return List.of(new Projection("business:procurement-payment:summary", "采购付款与原应付摘要", summary));
    }

    private List<Projection> budgetAdjustmentSources(String tenantId, java.util.UUID id, int roundNo) {
        var request = budgetAdjustments.find(tenantId, id).orElse(null);
        if (request == null) return List.of();
        var round = request.rounds().stream().filter(item -> item.roundNo() == roundNo).findFirst().orElse(null);
        if (round == null) return List.of();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("legalEntity", round.legalEntity().name());
        summary.put("type", round.content().type().name());
        summary.put("purpose", round.content().purpose());
        summary.put("accountingDate", round.content().accountingDate().toString());
        summary.put("amount", round.content().amount().value().toPlainString());
        summary.put("currency", round.content().amount().currency());
        if (round.content().sourceBudgetReference() != null) summary.put("sourceBudgetReference", round.content().sourceBudgetReference());
        if (round.content().targetBudgetReference() != null) summary.put("targetBudgetReference", round.content().targetBudgetReference());
        return List.of(new Projection("business:budget-adjustment:summary", "预算调整摘要", summary));
    }
}
