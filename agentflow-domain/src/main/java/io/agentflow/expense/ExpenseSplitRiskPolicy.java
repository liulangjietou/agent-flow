package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.ConditionParser;
import io.agentflow.definition.DefinitionModels.Comparison;
import io.agentflow.definition.DefinitionModels.ConditionAst;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Logical;
import io.agentflow.definition.DefinitionModels.Membership;
import io.agentflow.definition.DefinitionModels.Negation;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.finance.Money;
import io.agentflow.form.FormSchema;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跨单规则随流程版本显式发布，只有指定业务网关采用冻结合计，不改变财务复核金额。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseSplitRiskPolicy {
    public static final String MODE_PROPERTY = "expenseSplitRisk";
    public static final String WINDOW_PROPERTY = "expenseSplitWindowDays";
    public static final String THRESHOLD_PROPERTY = "expenseSplitThreshold";
    public static final String CURRENCY_PROPERTY = "expenseSplitCurrency";
    public static final String GATEWAY_PROPERTY = "expenseSplitRouting";
    public static final String AGGREGATE_AMOUNT = "AGGREGATE_AMOUNT";
    public static final int MAX_WINDOW_DAYS = 365;
    public static final int RULE_VERSION = 1;
    private static final List<String> RULE_PROPERTIES = List.of(WINDOW_PROPERTY, THRESHOLD_PROPERTY, CURRENCY_PROPERTY);
    private static final Set<String> START_PROPERTIES = Set.of(MODE_PROPERTY, WINDOW_PROPERTY, THRESHOLD_PROPERTY, CURRENCY_PROPERTY);
    private static final String WINDOW_PATTERN = "[1-9][0-9]{0,2}";
    private static final String AMOUNT_PATTERN = "(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,2})?";

    private ExpenseSplitRiskPolicy() { }

    /** 不把缺省解释成已关闭或已启用；启用参数和网关均须有明确来源。 */
    public static Configuration from(Graph graph) {
        Map<String, String> configured = null;
        var gateways = new TreeSet<String>();
        for (var node : graph.nodes()) {
            if (START_PROPERTIES.stream().anyMatch(node.properties()::containsKey)) {
                if (node.type() != NodeType.START || configured != null) throw invalid();
                configured = node.properties();
            }
            if (node.properties().containsKey(GATEWAY_PROPERTY)) {
                if (node.type() != NodeType.EXCLUSIVE_GATEWAY || !AGGREGATE_AMOUNT.equals(node.properties().get(GATEWAY_PROPERTY))) {
                    throw new DomainException("EXPENSE_SPLIT_ROUTING_INVALID", "Aggregate routing requires an explicit exclusive gateway");
                }
                gateways.add(node.id());
            }
        }
        if (configured == null) {
            if (!gateways.isEmpty()) throw invalid();
            return new Configuration(Mode.UNCONFIGURED, null, Set.of());
        }
        String mode = configured.get(MODE_PROPERTY);
        if (!Mode.ENABLED.name().equals(mode) && !Mode.DISABLED.name().equals(mode)) throw invalid();
        return new Configuration(Mode.valueOf(mode), rule(configured), gateways);
    }

    /** 同时约束表单、条件引用和财务之后的可达路径，关闭状态也不允许保存危险网关位置。 */
    public static void validate(Graph graph, FormSchema schema) {
        var configuration = from(graph);
        if (configuration.mode() == Mode.UNCONFIGURED) return;
        if (!ExpenseFormContract.structured(schema)) {
            throw new DomainException("EXPENSE_SPLIT_RISK_REQUIRES_EXPENSE_FORM", "Split risk policy requires a structured expense form");
        }
        ExpenseFormContract.requireSchema(schema);
        var parser = new ConditionParser();
        for (String gateway : configuration.gatewayIds()) {
            boolean usesAmount = graph.edges().stream().filter(edge -> gateway.equals(edge.source()) && !edge.condition().isBlank())
                    .anyMatch(edge -> referencesAmount(parser.parse(edge.condition(), graph.conditionLanguageVersion())));
            if (!usesAmount) throw new DomainException("EXPENSE_SPLIT_ROUTING_AMOUNT_REQUIRED", "Aggregate routing requires a condition on the expense amount");
        }
        requireBeforeFinance(graph, configuration.gatewayIds());
    }

    private static Rule rule(Map<String, String> properties) {
        long supplied = RULE_PROPERTIES.stream().filter(properties::containsKey).count();
        if (supplied == 0) return null;
        if (supplied != RULE_PROPERTIES.size()) throw invalid();
        String window = properties.get(WINDOW_PROPERTY), amount = properties.get(THRESHOLD_PROPERTY);
        if (window == null || !window.matches(WINDOW_PATTERN) || amount == null || !amount.matches(AMOUNT_PATTERN)) throw invalid();
        try { return new Rule(Integer.parseInt(window), new Money(new BigDecimal(amount), properties.get(CURRENCY_PROPERTY))); }
        catch (DomainException invalidMoney) { throw invalid(); }
    }

    private static boolean referencesAmount(ConditionAst condition) {
        if (condition instanceof Logical logical) return logical.terms().stream().anyMatch(ExpenseSplitRiskPolicy::referencesAmount);
        if (condition instanceof Negation negation) return referencesAmount(negation.term());
        String field = condition instanceof Membership membership ? membership.field() : ((Comparison) condition).field();
        return ExpenseFormContract.AMOUNT.equals(field);
    }

    private static void requireBeforeFinance(Graph graph, Set<String> gateways) {
        if (gateways.isEmpty()) return;
        var outgoing = new HashMap<String, List<String>>();
        for (var edge : graph.edges()) outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target());
        var pending = new ArrayDeque<String>();
        graph.nodes().stream().filter(node -> ExpenseProcessPolicy.stage(node).finance()).forEach(node -> pending.add(node.id()));
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            if (!visited.add(node)) continue;
            if (gateways.contains(node)) throw new DomainException("EXPENSE_SPLIT_ROUTING_AFTER_FINANCE", "Aggregate amount cannot replace financial review amount");
            pending.addAll(outgoing.getOrDefault(node, List.of()));
        }
    }

    private static DomainException invalid() { return new DomainException("EXPENSE_SPLIT_RISK_POLICY_INVALID", "Split risk configuration must explicitly define a supported rule"); }

    /**
     * 未配置与管理员明确关闭具有不同语义，任何一项都不能声称已完成风险计算。
     * @author owlzhangfq@gmail.com
     */
    public enum Mode { UNCONFIGURED, DISABLED, ENABLED }

    /**
     * 固定日数按完整 24 小时处理，金额沿用系统支持的两位本位币。
     * @author owlzhangfq@gmail.com
     */
    public record Rule(int windowDays, Money threshold) {
        /** 不提供业务默认值，也不接受不能表达有效门槛的零额。 */
        public Rule {
            if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS || threshold == null || threshold.value().signum() <= 0) throw invalid();
        }
    }

    /**
     * 保存发布时明确选择的业务网关；稳定排序保证跨进程序列化摘要一致。
     * @author owlzhangfq@gmail.com
     */
    public record Configuration(Mode mode, Rule rule, Set<String> gatewayIds) {
        /** 模式与参数组合在这里统一封闭，后续服务直接使用已经验证的配置。 */
        public Configuration {
            if (mode == null || gatewayIds == null || gatewayIds.stream().anyMatch(java.util.Objects::isNull)
                    || mode == Mode.UNCONFIGURED && (rule != null || !gatewayIds.isEmpty())
                    || mode == Mode.ENABLED && rule == null) throw invalid();
            gatewayIds = Collections.unmodifiableSet(new TreeSet<>(gatewayIds));
            if (mode == Mode.ENABLED && gatewayIds.isEmpty()) {
                throw new DomainException("EXPENSE_SPLIT_ROUTING_REQUIRED", "Enabled split risk policy requires a business routing gateway");
            }
        }
    }
}
