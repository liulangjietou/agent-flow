package io.agentflow.definition;

import java.util.stream.Collectors;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 将旧条件 AST 转为等价 v2 文本，先解析再转换，避免替换连接符改变字面量。
 * @author owlzhangfq@gmail.com
 */
public final class ConditionLanguageUpgrade {
    /** 返回独立快照，节点、连线顺序与属性不变，不保存或发布。 */
    public Graph upgrade(Graph graph) {
        if (graph.conditionLanguageVersion() == 2) return graph;
        ConditionParser parser = new ConditionParser();
        return new Graph(graph.nodes(), graph.edges().stream().map(edge -> {
            String condition = render(parser.parse(edge.condition(), 1));
            // 等价转换超出新版限制时整次失败，不能截断或部分升级。
            parser.parse(condition, 2);
            return new Edge(edge.id(), edge.source(), edge.target(), condition, edge.defaultBranch());
        }).toList(), 2);
    }

    private String render(ConditionAst ast) {
        if (ast instanceof Logical logical) {
            if (logical.terms().isEmpty()) return "";
            String join = logical.kind() == Kind.AND ? " AND " : " OR ";
            return "(" + logical.terms().stream().map(this::render).collect(Collectors.joining(join)) + ")";
        }
        Comparison comparison = (Comparison) ast;
        String operator = switch (comparison.operator()) {
            case EQ -> "=="; case NE -> "!="; case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<=";
            case EXISTS -> "EXISTS"; case NOT_EXISTS -> "NOT_EXISTS";
        };
        return comparison.field() + " " + operator
                + (comparison.operator() == Operator.EXISTS || comparison.operator() == Operator.NOT_EXISTS ? "" : " " + quote(comparison.literal()));
    }

    private String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> {
                    if (character < ' ') result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
