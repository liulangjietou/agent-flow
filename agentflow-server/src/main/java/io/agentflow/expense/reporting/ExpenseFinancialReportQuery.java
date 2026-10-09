package io.agentflow.expense.reporting;


import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.CurrentActor;
import io.agentflow.expense.ExpensePolicySnapshot;
import io.agentflow.expense.reporting.mapper.ExpenseFinancialReportQueryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 原审批轮次、当前财务资源和操作事实的只读编排，金额及样本运算留在领域层。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseFinancialReportQuery {
    private final ExpenseFinancialReportQueryMapper sqlMapper;
    private final CurrentActor actors;
    private final ExpenseReportScope scope;
    private final ExpenseReportingOperations operations;
    private final ExpenseReportingActivity activity;
    private final ExpenseReportingResources resources;

    /** 来源服务各自复用领域仓储和原授权，查询层不重建财务状态机。 */
    public ExpenseFinancialReportQuery(
            ExpenseFinancialReportQueryMapper sqlMapper,
            CurrentActor actors,
            ExpenseReportScope scope,
            ExpenseReportingOperations operations,
            ExpenseReportingActivity activity,
            ExpenseReportingResources resources) {
        this.sqlMapper = sqlMapper;
        this.actors = actors;
        this.scope = scope;
        this.operations = operations;
        this.activity = activity;
        this.resources = resources;
    }

    /** 一次一致性快照返回完整可读聚合；不以静默截断产生貌似完整的金额。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExpenseFinancialReport read(ExpenseReportQueryParameters.Query query, Instant now) {
        var candidates =
                SqlRows.map(
                        sqlMapper.read(
                                actors.actor().tenantId(),
                                Timestamp.from(query.start()),
                                Timestamp.from(query.end())),
                        row ->
                                new Candidate(
                                        UUID.fromString(row.getString("id")),
                                        row.getInt("round_no")));
        var sources = new ArrayList<ExpenseReportScope.Expense>();
        for (var candidate : candidates)
            scope.expense(candidate.id(), candidate.round())
                    .filter(value -> value.matches(query))
                    .ifPresent(sources::add);
        var facts = sources.stream().map(source -> new Fact(source, fact(source))).toList();
        var totals =
                ExpenseFinancialMetrics.summarize(
                        facts.stream().map(Fact::metrics).toList(), now, query.categoryCode());
        return new ExpenseFinancialReport(
                now,
                "UTC",
                "CURRENT_ACTOR_READABLE",
                query,
                totals,
                groups(facts, query.categoryCode(), now),
                activity.read(sources, query),
                resources.read(query, now),
                operations.backlog(query, now));
    }

    private ExpenseFinancialMetrics.Round fact(ExpenseReportScope.Expense source) {
        var original = source.original(); var financial = source.view().financialRound();
        var approved = financial.approvedLines().stream().collect(Collectors.toMap(line -> line.lineNo(), line -> line.gross()));
        var lines = financial.originalLines().stream().map(line -> new ExpenseFinancialMetrics.Line(line.original().categoryCode(), line.claimedBase(),
                approved.get(line.original().lineNo()), line.assessment().policy().decision() == ExpensePolicySnapshot.Decision.REQUIRES_EXCEPTION)).toList();
        var arrival = original.status() == SubmissionRound.Status.APPROVED ? operations.arrival(source)
                : new ExpenseReportingOperations.Arrival(ExpenseFinancialMetrics.Payment.AWAITING, null);
        return new ExpenseFinancialMetrics.Round(original.status(), original.submittedAt(), original.completedAt(), arrival.state(), arrival.at(), lines, original.reason());
    }

    private static List<ExpenseFinancialReport.Group> groups(List<Fact> facts, String category, Instant now) {
        var ordering = Comparator.comparing(GroupKey::dimension).thenComparing(GroupKey::code, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(GroupKey::name, Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<GroupKey, List<ExpenseFinancialMetrics.Round>> groups = new TreeMap<>(ordering);
        for (var fact : facts) {
            var original = fact.source().original().initiatorContext();
            add(groups, new GroupKey("LEGAL_ENTITY", original == null ? null : original.legalEntityId().toString(), original == null ? null : original.legalEntityName()), fact.metrics());
            add(groups, new GroupKey("DEPARTMENT", original == null ? null : original.departmentId().toString(), original == null ? null : original.departmentName()), fact.metrics());
            fact.metrics().lines().stream().map(ExpenseFinancialMetrics.Line::categoryCode).distinct().filter(code -> category == null || category.equals(code))
                    .forEach(code -> add(groups, new GroupKey("CATEGORY", code, code), fact.metrics()));
        }
        return groups.entrySet().stream().map(entry -> new ExpenseFinancialReport.Group(entry.getKey().dimension(), entry.getKey().code(), entry.getKey().name(),
                ExpenseFinancialMetrics.summarize(entry.getValue(), now, entry.getKey().dimension().equals("CATEGORY") ? entry.getKey().code() : category))).toList();
    }

    private static void add(Map<GroupKey, List<ExpenseFinancialMetrics.Round>> groups, GroupKey key, ExpenseFinancialMetrics.Round value) {
        groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    /**
     * 查询第一步只有索引标识，不能先读取整租户敏感金额再过滤。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Candidate(UUID id, int round) {}

    /**
     * 已授权原轮次及一次派生的指标输入，分组不重复查询付款。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Fact(ExpenseReportScope.Expense source, ExpenseFinancialMetrics.Round metrics) {}

    /**
     * 原组织名称变化时保留不同快照分组，不重写先前名称。
     *
     * @author owlzhangfq@gmail.com
     */
    private record GroupKey(String dimension, String code, String name) {}
}
