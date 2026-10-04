package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 在费用服务已绑定的事实中计算跨单业务路由依据，财务金额不受路由计算改写。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseSplitRiskEvidence {
    public static final int MAX_DOCUMENTS = 1000;
    private static final int MAX_IDENTITY_LENGTH = 128;
    private static final int MAX_CATEGORY_LENGTH = 64;
    private static final Comparator<Document> SOURCE_ORDER = Comparator.comparing(Document::submittedAt)
            .thenComparing(document -> document.reportId().toString());
    private ExpenseSplitRiskEvidence() { }

    /** 冻结本次提交所用的窗口、原财务版本和路由金额。 */
    public static Assessment assess(ExpenseSplitRiskPolicy.Rule rule, Document primary, List<Document> candidates, Instant at) {
        if (rule == null || primary == null || candidates == null || candidates.stream().anyMatch(java.util.Objects::isNull)
                || at == null || !at.equals(primary.submittedAt())) throw invalid();
        if (!rule.threshold().currency().equals(primary.scope().currency())) {
            throw new DomainException("EXPENSE_SPLIT_CURRENCY_MISMATCH", "Split risk threshold must use the submitted base currency");
        }
        Instant from = at.minus(Duration.ofDays(rule.windowDays()));
        var own = primary.lines().stream().map(Line::approvedGross).reduce(Money.zero(primary.scope().currency()), Money::plus);
        var totals = positiveCategories(primary);
        var counts = new TreeMap<String, Integer>();
        totals.keySet().forEach(category -> counts.put(category, 1));
        var sources = new ArrayList<Document>(); sources.add(primary);
        var reports = new HashSet<UUID>(); reports.add(primary.reportId());
        var applications = new HashSet<UUID>(); applications.add(primary.applicationId());
        for (var candidate : candidates) {
            if (primary.reportId().equals(candidate.reportId()) || !primary.scope().equals(candidate.scope())
                    || candidate.status() != ApplicationStatus.IN_APPROVAL && candidate.status() != ApplicationStatus.APPROVED
                    || candidate.submittedAt().isBefore(from) || candidate.submittedAt().isAfter(at)) continue;
            var amounts = positiveCategories(candidate);
            amounts.keySet().retainAll(totals.keySet());
            if (amounts.isEmpty()) continue;
            if (!reports.add(candidate.reportId()) || !applications.add(candidate.applicationId())) throw invalid();
            if (sources.size() == MAX_DOCUMENTS) {
                throw new DomainException("EXPENSE_SPLIT_SOURCE_LIMIT", "Split risk source count exceeds the supported limit");
            }
            sources.add(candidate);
            // 同单先合并同类行，再增加一次单据数量；不能把零额和多行误算成多单。
            amounts.forEach((category, amount) -> {
                totals.merge(category, amount, Money::plus); counts.merge(category, 1, Integer::sum);
            });
        }
        sources.subList(1, sources.size()).sort(SOURCE_ORDER);
        var categories = totals.entrySet().stream().map(entry -> new CategoryTotal(entry.getKey(), entry.getValue(), counts.get(entry.getKey()),
                counts.get(entry.getKey()) > 1 && entry.getValue().compareTo(rule.threshold()) > 0)).toList();
        // 多类别不能相加，否则同一来源可能重复放大金额；本单总额始终是最低路由金额。
        var routing = categories.stream().filter(CategoryTotal::triggered).map(CategoryTotal::total)
                .reduce(own, (left, right) -> left.compareTo(right) >= 0 ? left : right);
        return new Assessment(from, at, own, routing, categories, sources);
    }

    private static Map<String, Money> positiveCategories(Document document) {
        var totals = new TreeMap<String, Money>();
        for (var line : document.lines()) if (line.approvedGross().value().signum() > 0) {
            totals.merge(line.categoryCode(), line.approvedGross(), Money::plus);
        }
        return totals;
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_SPLIT_EVIDENCE_INVALID", "Split risk evidence must retain unique current report and line bindings");
    }

    /**
     * 同租户、申请人、法人及本位币是费用跨单比较的边界。
     * @author owlzhangfq@gmail.com
     */
    public record Scope(String tenantId, String employeeId, UUID legalEntityId, String currency) {
        /** 只接受实际费用归属，币种沿用系统的两位金额范围。 */
        public Scope {
            if (StringUtils.isBlank(tenantId) || tenantId.length() > MAX_IDENTITY_LENGTH || StringUtils.isBlank(employeeId)
                    || employeeId.length() > MAX_IDENTITY_LENGTH || legalEntityId == null) throw invalid();
            Money.zero(currency);
        }
    }

    /**
     * 保留原行号和当前核定的本位币含税金额。
     * @author owlzhangfq@gmail.com
     */
    public record Line(int lineNo, String categoryCode, Money approvedGross) {
        /** 核减为零仍是有效行事实，但不增加跨单参与数量。 */
        public Line {
            if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || StringUtils.isBlank(categoryCode)
                    || categoryCode.length() > MAX_CATEGORY_LENGTH || approvedGross == null) throw invalid();
        }
    }

    /**
     * 服务从当前有效财务轮次读取的不可变来源。
     * @author owlzhangfq@gmail.com
     */
    public record Document(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, int roundNo,
                           Scope scope, Instant submittedAt, ApplicationStatus status, List<Line> lines) {
        /** 在输入边界校验完整版本和唯一行号，调用者不能事后修改来源行集合。 */
        public Document {
            if (reportId == null || applicationId == null || applicationVersion < 1 || financialVersion < 1 || roundNo < 1
                    || scope == null || submittedAt == null || status == null || CollectionUtils.isEmpty(lines)
                    || lines.size() > ExpenseContent.MAX_LINES) throw invalid();
            var numbers = new HashSet<Integer>();
            for (var line : lines) if (line == null || !numbers.add(line.lineNo()) || !scope.currency().equals(line.approvedGross().currency())) throw invalid();
            lines = lines.stream().sorted(Comparator.comparingInt(Line::lineNo)).toList();
        }

        /** 当前查询和历史修订恢复共用相同映射，类别来自原冻结行，金额来自当前核定行。 */
        public static Document from(ExpenseReport report, long applicationVersion, ApplicationStatus status) {
            var round = report.requireFrozenRound();
            var categories = new TreeMap<Integer, String>();
            for (var line : round.originalLines()) categories.put(line.original().lineNo(), line.original().categoryCode());
            return new Document(report.id(), report.applicationId(), applicationVersion, report.version(), round.roundNo(),
                    new Scope(report.tenantId(), report.employeeId(), round.content().legalEntityId(), round.baseCurrency()), round.submittedAt(), status,
                    round.approvedLines().stream().map(line -> new Line(line.lineNo(), categories.get(line.lineNo()), line.gross())).toList());
        }
    }

    /**
     * 同类别只计算不同报销单中的正核定金额。
     * @author owlzhangfq@gmail.com
     */
    public record CategoryTotal(String categoryCode, Money total, int reportCount, boolean triggered) { }

    /**
     * 完整冻结的规则计算结果，与后续来源状态和财务核减隔离。
     * @author owlzhangfq@gmail.com
     */
    public record Assessment(Instant windowFrom, Instant assessedAt, Money ownAmount, Money routingAmount,
                             List<CategoryTotal> categories, List<Document> sources) {
        /** 固定来源和类别顺序，不引用调用方可变集合。 */
        public Assessment { categories = List.copyOf(categories); sources = List.copyOf(sources); }
        /** 是否至少有一个类别达到跨单数量和严格金额阈值。 */
        public boolean suspected() { return categories.stream().anyMatch(CategoryTotal::triggered); }
    }
}
