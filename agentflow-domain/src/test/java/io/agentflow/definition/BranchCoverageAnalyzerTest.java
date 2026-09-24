package io.agentflow.definition;

import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.definition.BranchCoverageAnalyzer.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖证明以可提交的精确数值为依据，边界、缺失值和历史语义不能被近似。
 * @author owlzhangfq@gmail.com
 */
class BranchCoverageAnalyzerTest {
    private final BranchCoverageAnalyzer analyzer = new BranchCoverageAnalyzer();

    @Test
    void reportsGapWithARealCounterexampleAndDefaultOnlySuppressesGap() {
        var graph = graph(false, "amount <= 5000", "amount > 8000");
        var diagnostics = analyzer.analyze(graph, schema(true, null, null));
        assertThat(diagnostics).hasSize(1);
        var gap = diagnostics.get(0);
        assertThat(gap.code()).isEqualTo(Code.BRANCH_COVERAGE_GAP);
        assertThat(gap.severity()).isEqualTo(Severity.ERROR);
        assertThat(gap.gatewayId()).isEqualTo("gate");
        assertThat(new java.math.BigDecimal(gap.sampleValue())).isGreaterThan(new java.math.BigDecimal("5000")).isLessThanOrEqualTo(new java.math.BigDecimal("8000"));
        assertThat(analyzer.analyze(graph(true, "amount <= 5000", "amount > 8000"), schema(true, null, null))).isEmpty();
        var overlap = analyzer.analyze(graph(true, "amount <= 5000", "amount >= 5000"), schema(true, null, null));
        assertThat(overlap).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(Code.BRANCH_OVERLAP);
            assertThat(issue.edgeIds()).containsExactly("route-0", "route-1");
            assertThat(issue.sampleValue()).isEqualTo("5000");
        });
    }

    @Test
    void exactBoundaryAndFormBoundsDetermineCoverage() {
        assertThat(analyzer.analyze(graph(false, "amount <= 5000", "amount > 5000"), schema(true, null, null))).isEmpty();
        assertThat(analyzer.analyze(graph(false, "amount < 5000", "amount > 5000"), schema(true, null, null)))
                .singleElement().satisfies(issue -> assertThat(issue.sampleValue()).isEqualTo("5000"));
        assertThat(analyzer.analyze(graph(false, "amount < 5000", "amount >= 8000"), schema(true, "8000", "9000"))).isEmpty();
        assertThat(analyzer.analyze(graph(false, "amount < 0", "amount > 0"), schema(true, "0", "0")))
                .singleElement().satisfies(issue -> assertThat(issue.sampleValue()).isEqualTo("0"));
    }

    @Test
    void includesMissingOptionalValuesAndEvaluatesNegationLikeRuntime() {
        assertThat(analyzer.analyze(graph(false, "amount <= 1", "amount > 1"), schema(false, null, null)))
                .singleElement().satisfies(issue -> { assertThat(issue.missingValue()).isTrue(); assertThat(issue.sampleValue()).isNull(); });
        assertThat(analyzer.analyze(graph(false, "amount > 1", "!(amount > 1)"), schema(false, null, null))).isEmpty();
        assertThat(analyzer.analyze(graph(false, "amount EXISTS", "amount NOT_EXISTS"), schema(false, null, null))).isEmpty();
        assertThat(analyzer.analyze(graph(false, "amount == 1", "amount != 1"), schema(true, null, null))).isEmpty();
    }

    @Test
    void handlesGroupedDisconnectedRangesWithoutChangingFirstMatchOrder() {
        var graph = graph(false, "(amount > 1 && amount < 3) || amount == 5", "!(amount > 1 && amount < 3) && amount != 5");
        assertThat(analyzer.analyze(graph, schema(true, null, null))).isEmpty();
        assertThat(graph.edges().get(1).id()).isEqualTo("route-0");
        var overlap = analyzer.analyze(graph(false, "amount >= 0", "amount <= 2", "amount > 2"), schema(true, "0", "5"));
        assertThat(overlap).extracting(Diagnostic::edgeIds).contains(List.of("route-0", "route-1"), List.of("route-0", "route-2"));
    }

    @Test
    void neverReportsAValueOutsideDecimalPrecisionOrScaleLimits() {
        String max = "99999999999999999999999999999999999999";
        assertThat(analyzer.analyze(graph(false, "amount <= " + max, "amount > " + max), schema(true, null, null))).isEmpty();
        assertThat(analyzer.analyze(graph(false, "amount <= 0", "amount >= 0.000000000000000001"), schema(true, null, null))).isEmpty();
        var gap = analyzer.analyze(graph(false, "amount <= 0", "amount >= 0.000000000000000002"), schema(true, null, null));
        assertThat(gap).singleElement().satisfies(issue -> assertThat(issue.sampleValue()).isEqualTo("0.000000000000000001"));
        gap.forEach(issue -> schema(true, null, null).validateSubmission(Map.of("amount", issue.sampleValue())));
    }

    @Test
    void unsupportedMixedFieldsOrMissingSchemaProduceAnExplicitWarningWithoutDefault() {
        var mixed = new FormSchema(1, List.of(field("amount", true, null, null), field("count", true, null, null)));
        assertThat(analyzer.analyze(graph(false, "amount > 1", "count <= 1"), mixed)).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo(Code.BRANCH_COVERAGE_UNPROVEN));
        assertThat(analyzer.analyze(graph(false, "amount > 1", "amount <= 1"), null)).singleElement()
                .satisfies(issue -> assertThat(issue.severity()).isEqualTo(Severity.WARNING));
        assertThat(analyzer.analyze(graph(true, "amount > 1", "count <= 1"), mixed)).isEmpty();
    }

    @Test
    void legacySyntaxAndAbsentNumericIntervalsDoNotProduceFalseErrors() {
        Graph current = graph(false, "amount <= 5000", "amount > 5000");
        assertThat(analyzer.analyze(new Graph(current.nodes(), current.edges(), 1), schema(true, null, null))).isEmpty();
        var none = graph(false, "amount < 1 && amount > 3", "amount EXISTS");
        assertThat(analyzer.analyze(none, schema(true, null, null))).isEmpty();
        String maximum = "99999999999999999999999999999999999999";
        assertThat(analyzer.analyze(graph(false, "amount < -" + maximum, "amount >= -" + maximum), schema(true, null, null))).isEmpty();
    }

    @Test
    void boundedAnalysisReportsUnprovenInsteadOfClaimingSuccessAndResultsAreImmutable() {
        String[] conditions = java.util.stream.IntStream.range(0, 5).mapToObj(branch -> java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "amount != " + (branch * 100 + index)).collect(java.util.stream.Collectors.joining(" AND "))).toArray(String[]::new);
        var diagnostics = analyzer.analyze(graph(false, conditions), schema(true, null, null));
        assertThat(diagnostics).singleElement().satisfies(issue -> assertThat(issue.code()).isEqualTo(Code.BRANCH_COVERAGE_UNPROVEN));
        assertThatThrownBy(diagnostics::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> diagnostics.get(0).edgeIds().add("forged")).isInstanceOf(UnsupportedOperationException.class);
    }

    private FormSchema schema(boolean required, String minimum, String maximum) {
        return new FormSchema(1, List.of(field("amount", required, minimum, maximum)));
    }
    private FormSchema.Field field(String key, boolean required, String minimum, String maximum) {
        return new FormSchema.Field(key, key, FormSchema.FieldType.NUMBER, required, null, null, minimum, maximum, null);
    }
    private Graph graph(boolean fallback, String... conditions) {
        var nodes = List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("gate", "金额分支", NodeType.EXCLUSIVE_GATEWAY, Map.of()), new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new java.util.ArrayList<Edge>();
        edges.add(new Edge("start-gate", "start", "gate", ""));
        for (int i = 0; i < conditions.length; i++) edges.add(new Edge("route-" + i, "gate", "end", conditions[i]));
        if (fallback) edges.add(new Edge("default", "gate", "end", "", true));
        return new Graph(nodes, edges, 2);
    }
}
