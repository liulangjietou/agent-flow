package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存前在事务外取得本人适用的补贴规则，写入时在配置锁内复核选择与事实有效期。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseAllowancePreparation {
    private final CurrentActor actors;
    private final ExpensePolicyConfiguration policies;
    private final ExpensePolicyGuidanceService guidance;
    private final FinanceGatewayConfiguration gateway;

    /** 复用填报提示的可信匹配链路，不新增可被客户端伪造的补贴报价票据。 */
    public ExpenseAllowancePreparation(CurrentActor actors, ExpensePolicyConfiguration policies,
            ExpensePolicyGuidanceService guidance, FinanceGatewayConfiguration gateway) {
        this.actors = actors; this.policies = policies; this.guidance = guidance; this.gateway = gateway;
    }

    /** 普通行不读取外部事实；可能命中补贴的行必须重新取得适用规则并由领域公式重算。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(ExpenseContent content) {
        var actor = actors.actor();
        var snapshot = policies.snapshot(actor.tenantId());
        String target = null;
        Instant validUntil = null;
        var lines = new ArrayList<ExpenseLine>();
        for (var line : content.lines()) {
            boolean candidate = snapshot.selection() != null && snapshot.current().activePolicy().definition().rules().stream()
                    .anyMatch(rule -> rule.constraints().fixedAllowance() != null && rule.match().categoryCodes().contains(line.categoryCode()));
            if (!candidate) {
                if (line.allowance() != null) throw recalculationRequired();
                lines.add(line);
                continue;
            }
            if (target == null) target = target(actor.tenantId());
            var view = guidance.read(new ExpensePolicyGuidance.Context(content.legalEntityId(), content.type(), line.categoryCode(),
                    line.cityCode(), line.incurredOn(), line.claimedGross().currency(), line.unit(), line.endedOn()));
            if (!snapshot.selection().equals(view.guidance().selection())) throw configurationChanged();
            if (validUntil == null || view.guidance().validUntil().isBefore(validUntil)) validUntil = view.guidance().validUntil();
            if (view.guidance().constraints().fixedAllowance() == null) {
                if (line.allowance() != null) throw recalculationRequired();
                lines.add(line);
            } else {
                if (view.allowance() == null) throw new DomainException("ALLOWANCE_ITINERARY_REQUIRED", "Allowance requires the itinerary end date");
                // 客户端可回显旧依据，但落库只能使用本次本人匹配得到的来源；金额和天数不一致直接拒绝。
                lines.add(line.withAllowance(view.allowance()));
            }
        }
        return new Prepared(actor.tenantId(), actor.userId(), snapshot.selection(), target, validUntil,
                new ExpenseContent(content.legalEntityId(), content.type(), content.title(), lines, content.advanceOffsets()));
    }

    /** 所有草稿保存都持有发布共用锁，防止无补贴准备之后并发启用新补贴制度。 */
    public ExpenseContent requireCurrent(Prepared prepared) {
        var actor = actors.actor();
        if (!actor.tenantId().equals(prepared.tenantId()) || !actor.userId().equals(prepared.employeeId())) {
            throw new DomainException("FORBIDDEN", "Allowance preparation belongs to another employee");
        }
        policies.lockForSubmission(actor.tenantId());
        if (!policies.current(actor.tenantId(), prepared.selection())) throw configurationChanged();
        if (prepared.targetDigest() != null && !prepared.targetDigest().equals(target(actor.tenantId()))) {
            throw new DomainException("FINANCE_TARGET_CHANGED", "Finance destination changed while preparing the allowance");
        }
        if (prepared.validUntil() != null && !prepared.validUntil().isAfter(Instant.now())) {
            throw new DomainException("FACTS_EXPIRED", "Allowance preparation facts expired before saving");
        }
        return prepared.content();
    }

    private String target(String tenant) {
        return gateway.destination(tenant).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "NOT_CONFIGURED")).digest(tenant);
    }
    private static DomainException configurationChanged() { return new DomainException("POLICY_CONFIGURATION_CHANGED", "Expense policy changed while preparing the draft"); }
    private static DomainException recalculationRequired() { return new DomainException("ALLOWANCE_RECALCULATION_REQUIRED", "Allowance must be recalculated against the current published rule"); }

    /**
     * 仅在同一次服务端调用中传递，持久化仍复用费用内容版本。
     * @author owlzhangfq@gmail.com
     */
    public record Prepared(String tenantId, String employeeId, ExpensePolicySelection selection, String targetDigest,
                           Instant validUntil, ExpenseContent content) { }
}
