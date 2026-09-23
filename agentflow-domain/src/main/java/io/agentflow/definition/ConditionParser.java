package io.agentflow.definition;

import io.agentflow.common.DomainException;

import java.util.ArrayList;
import java.util.List;

import static io.agentflow.definition.DefinitionModels.*;

/**
 * 解析受限条件语法；不执行任意表达式、JUEL、脚本或 Java 代码。
 * @author owlzhangfq@gmail.com
 */
public final class ConditionParser {
    /** 使用发布时绑定的语法版本；缺省入口继续保留历史语义。 */
    public ConditionAst parse(String source, int version) {
        if (version != 1 && version != 2) throw new DomainException("INVALID_CONDITION_VERSION", "Unsupported condition language version");
        return version == 1 ? parse(source) : new ConditionV2Parser(source).parse();
    }

    /** 解析旧条件。语法：field op literal，可用 AND/OR 组合。 */
    public ConditionAst parse(String source) {
        if (source == null || source.isBlank()) return new Logical(Kind.AND, List.of());
        List<String> orParts = splitOutsideQuotes(source, "OR");
        List<ConditionAst> ors = new ArrayList<>();
        for (String or : orParts) {
            List<String> andParts = splitOutsideQuotes(or, "AND");
            List<ConditionAst> ands = new ArrayList<>();
            for (String token : andParts) ands.add(parseComparison(token.trim()));
            ors.add(ands.size() == 1 ? ands.get(0) : new Logical(Kind.AND, ands));
        }
        return ors.size() == 1 ? ors.get(0) : new Logical(Kind.OR, ors);
    }

    /** 只在完整字面量之外拆分连接符；先拆 OR 再拆 AND，保持原有优先级。 */
    private List<String> splitOutsideQuotes(String source, String connector) {
        List<String> parts = new ArrayList<>();
        int partStart = 0;
        int wordCount = 0;
        boolean inWord = false;
        char quote = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (current == quote) quote = 0;
                continue;
            }
            if (separator(current)) {
                inWord = false;
                continue;
            }
            String found = connectorAt(source, index, "AND") ? "AND"
                    : connectorAt(source, index, "OR") ? "OR" : null;
            if (found != null) {
                if (found.equals(connector)) {
                    parts.add(source.substring(partStart, index));
                    partStart = index + found.length();
                }
                // 即使本轮只拆另一种连接符，下一比较式的第三个词仍是字面量起点。
                wordCount = 0;
                inWord = false;
                index += found.length() - 1;
            } else if (!inWord) {
                wordCount++;
                inWord = true;
                // 仅字面量首字符开启引用，保留 O'Reilly 等原有无引号值。
                if (wordCount == 3 && (current == '\'' || current == '"')) quote = current;
            }
        }
        if (quote != 0) throw invalidQuotedLiteral();
        parts.add(source.substring(partStart));
        return parts;
    }

    private boolean connectorAt(String source, int index, String connector) {
        int end = index + connector.length();
        return index > 0 && end < source.length() && separator(source.charAt(index - 1))
                && separator(source.charAt(end)) && source.regionMatches(true, index, connector, 0, connector.length());
    }

    private boolean separator(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == '\u000B';
    }

    private ConditionAst parseComparison(String token) {
        if (token.contains(";") || token.contains("${") || token.contains("#{") || token.contains("(") || token.contains(")")) {
            throw new DomainException("INVALID_CONDITION", "Only the allowlisted condition grammar is supported");
        }
        String[] p = token.split("\\s+", 3);
        if (p.length == 2 && (p[1].equalsIgnoreCase("EXISTS") || p[1].equalsIgnoreCase("NOT_EXISTS"))) {
            return new Comparison(p[0], p[1].equalsIgnoreCase("EXISTS") ? Operator.EXISTS : Operator.NOT_EXISTS, "");
        }
        if (p.length != 3) throw new DomainException("INVALID_CONDITION", "Condition must be field operator literal");
        Operator op = switch (p[1]) { case "==" -> Operator.EQ; case "!=" -> Operator.NE; case ">" -> Operator.GT; case ">=" -> Operator.GE; case "<" -> Operator.LT; case "<=" -> Operator.LE; default -> throw new DomainException("INVALID_CONDITION", "Operator is not allowlisted"); };
        String literal = p[2];
        char first = literal.charAt(0);
        if (first == '\'' || first == '"') {
            // 完整引用后不能跟随文字或第二段引用，避免把无效配置当成普通字符串。
            if (literal.indexOf(first, 1) != literal.length() - 1) throw invalidQuotedLiteral();
            literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.length() > 256) throw new DomainException("INVALID_CONDITION", "Literal is too long");
        return new Comparison(p[0], op, literal);
    }

    private DomainException invalidQuotedLiteral() {
        return new DomainException("INVALID_CONDITION", "Quoted literal must be closed and cannot have trailing text");
    }
}
