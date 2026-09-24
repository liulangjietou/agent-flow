package io.agentflow.definition;

import io.agentflow.form.FormSchema;
import java.util.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 分析同一数字字段的分支覆盖；不改变分支顺序或运行时求值。
 * @author owlzhangfq@gmail.com
 */
public final class BranchCoverageAnalyzer {
    private static final long MAX_COMPARISON_EVALUATIONS = 250_000;
    private static final BigInteger MAX_UNSCALED = BigInteger.TEN.pow(FormSchema.MAX_DECIMAL_PRECISION).subtract(BigInteger.ONE);

    /** 对已通过结构及类型校验的图给出可复现的分支诊断。 */
    public List<Diagnostic> analyze(Graph graph, FormSchema schema) {
        List<Diagnostic> result = new ArrayList<>();
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
        graph.edges().forEach(edge -> outgoing.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge));
        for (Node node : graph.nodes()) {
            if (node.type() == NodeType.EXCLUSIVE_GATEWAY) {
                result.addAll(analyzeGateway(node.id(), outgoing.getOrDefault(node.id(), List.of()), graph.conditionLanguageVersion(), schema));
            }
        }
        return List.copyOf(result);
    }

    private List<Diagnostic> analyzeGateway(String gateway, List<Edge> outgoing, int version, FormSchema schema) {
        boolean fallback = outgoing.stream().anyMatch(Edge::defaultBranch);
        List<Edge> branches = outgoing.stream().filter(edge -> !edge.defaultBranch()).toList();
        if (schema == null) return unproven(gateway, fallback);
        ConditionParser parser = new ConditionParser();
        List<ConditionAst> conditions = branches.stream().map(edge -> parser.parse(edge.condition(), version)).toList();
        Set<String> fields = new HashSet<>();
        int comparisons = conditions.stream().mapToInt(condition -> inspect(condition, fields)).sum();
        if (fields.size() != 1) return unproven(gateway, fallback);
        FormSchema.Field field = schema.fields().stream().filter(candidate -> fields.contains(candidate.key())).findFirst().orElse(null);
        if (field == null || field.type() != FormSchema.FieldType.NUMBER) return unproven(gateway, fallback);
        NavigableSet<BigDecimal> boundaries = new TreeSet<>();
        conditions.forEach(condition -> boundaries(condition, boundaries));
        if (field.minimum() != null) boundaries.add(FormSchema.decimal(field.minimum()));
        if (field.maximum() != null) boundaries.add(FormSchema.decimal(field.maximum()));
        // 每个边界点及相邻开区间内，所有比较项的真值恒定，无需展开为指数级逻辑组合。
        if ((2L * boundaries.size() + 1) * comparisons > MAX_COMPARISON_EVALUATIONS) return unproven(gateway, fallback);
        Map<Signature, Diagnostic> issues = new LinkedHashMap<>();
        BigDecimal previous = null;
        for (BigDecimal boundary : boundaries) {
            probe(interior(previous, boundary), field, gateway, branches, conditions, fallback, issues);
            probe(boundary, field, gateway, branches, conditions, fallback, issues);
            previous = boundary;
        }
        probe(interior(previous, null), field, gateway, branches, conditions, fallback, issues);
        if (!field.required()) diagnose(null, true, field.key(), gateway, branches, conditions, fallback, issues);
        return List.copyOf(issues.values());
    }

    private int inspect(ConditionAst ast, Set<String> fields) {
        if (ast instanceof Comparison comparison) { fields.add(comparison.field()); return 1; }
        if (ast instanceof Membership membership) { fields.add(membership.field()); return 1; }
        if (ast instanceof Negation negation) return inspect(negation.term(), fields);
        return ((Logical) ast).terms().stream().mapToInt(term -> inspect(term, fields)).sum();
    }

    private void boundaries(ConditionAst ast, Set<BigDecimal> values) {
        if (ast instanceof Comparison comparison) {
            if (comparison.operator() != Operator.EXISTS && comparison.operator() != Operator.NOT_EXISTS) {
                values.add(FormSchema.decimal(comparison.literal()));
            }
        } else if (ast instanceof Negation negation) boundaries(negation.term(), values);
        else if (ast instanceof Logical logical) logical.terms().forEach(term -> boundaries(term, values));
    }

    private void probe(BigDecimal value, FormSchema.Field field, String gateway, List<Edge> branches,
                       List<ConditionAst> conditions, boolean fallback, Map<Signature, Diagnostic> issues) {
        if (value == null) return;
        String text = canonical(value);
        // 只报告可提交的数据，表单范围以外的数学空隙不是业务遗漏。
        try { FormSchema.decimal(text); } catch (IllegalArgumentException outsidePrecision) { return; }
        if (field.minimum() != null && value.compareTo(FormSchema.decimal(field.minimum())) < 0
                || field.maximum() != null && value.compareTo(FormSchema.decimal(field.maximum())) > 0) return;
        diagnose(text, false, field.key(), gateway, branches, conditions, fallback, issues);
    }

    private void diagnose(String value, boolean missing, String field, String gateway, List<Edge> branches,
                          List<ConditionAst> conditions, boolean fallback, Map<Signature, Diagnostic> issues) {
        EvaluationContext context = new EvaluationContext(missing ? Map.of() : Map.of(field, value), Map.of(field, "NUMBER"));
        List<String> matched = new ArrayList<>();
        for (int index = 0; index < conditions.size(); index++) {
            if (conditions.get(index).evaluate(context)) matched.add(branches.get(index).id());
        }
        if (matched.isEmpty() && !fallback) {
            var key = new Signature(Code.BRANCH_COVERAGE_GAP, List.of(), missing);
            issues.putIfAbsent(key, new Diagnostic(Severity.ERROR, key.code(), gateway, field, List.of(), value, missing));
        } else if (matched.size() > 1) {
            var key = new Signature(Code.BRANCH_OVERLAP, List.copyOf(matched), missing);
            issues.putIfAbsent(key, new Diagnostic(Severity.WARNING, key.code(), gateway, field, matched, value, missing));
        }
    }

    private List<Diagnostic> unproven(String gateway, boolean fallback) {
        return fallback ? List.of() : List.of(new Diagnostic(Severity.WARNING, Code.BRANCH_COVERAGE_UNPROVEN,
                gateway, "", List.of(), null, false));
    }

    /** 返回开区间内一个符合平台精度的数字；不存在可提交数值时返回 null。 */
    private BigDecimal interior(BigDecimal lower, BigDecimal upper) {
        BigDecimal preferred = lower == null ? upper == null ? BigDecimal.ZERO : upper.subtract(BigDecimal.ONE)
                : upper == null ? lower.add(BigDecimal.ONE) : lower.add(upper).divide(BigDecimal.valueOf(2));
        try { return FormSchema.decimal(canonical(preferred)); }
        catch (IllegalArgumentException ignored) {
            // 极窄区间的中点可能超过 18 位小数，必须检查可表示格点，不能将近似值当作遗漏。
            for (int scale = 0; scale <= FormSchema.MAX_DECIMAL_SCALE; scale++) {
                BigInteger minimum = lower == null ? MAX_UNSCALED.negate()
                        : lower.movePointRight(scale).setScale(0, RoundingMode.FLOOR).toBigIntegerExact().add(BigInteger.ONE).max(MAX_UNSCALED.negate());
                BigInteger maximum = upper == null ? MAX_UNSCALED
                        : upper.movePointRight(scale).setScale(0, RoundingMode.CEILING).toBigIntegerExact().subtract(BigInteger.ONE).min(MAX_UNSCALED);
                if (minimum.compareTo(maximum) <= 0) return new BigDecimal(minimum.add(maximum).divide(BigInteger.TWO), scale);
            }
            return null;
        }
    }

    private String canonical(BigDecimal number) { return number.stripTrailingZeros().toPlainString(); }

    /**
     * 每组同时命中的分支只报告一个精确反例，避免重复淹没校验面板。
     * @author owlzhangfq@gmail.com
     */
    private record Signature(Code code, List<String> edges, boolean missing) { }

    /**
     * 诊断严重程度。
     * @author owlzhangfq@gmail.com
     */
    public enum Severity { ERROR, WARNING }
    /**
     * 稳定的分支诊断码。
     * @author owlzhangfq@gmail.com
     */
    public enum Code { BRANCH_COVERAGE_GAP, BRANCH_OVERLAP, BRANCH_COVERAGE_UNPROVEN }
    /**
     * 精确示例值按字符串输出，空值与未能分析通过标志和诊断码区分。
     * @author owlzhangfq@gmail.com
     */
    public record Diagnostic(Severity severity, Code code, String gatewayId, String field,
                             List<String> edgeIds, String sampleValue, boolean missingValue) {
        public Diagnostic { edgeIds = List.copyOf(edgeIds); }
    }
}
