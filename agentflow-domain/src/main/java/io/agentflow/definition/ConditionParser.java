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
    /** 解析条件。语法：field op literal，可用 AND/OR 组合。 */
    public ConditionAst parse(String source) {
        if (source == null || source.isBlank()) return new Logical(Kind.AND, List.of());
        String[] orParts = source.split("(?i)\\s+OR\\s+");
        List<ConditionAst> ors = new ArrayList<>();
        for (String or : orParts) {
            String[] andParts = or.split("(?i)\\s+AND\\s+");
            List<ConditionAst> ands = new ArrayList<>();
            for (String token : andParts) ands.add(parseComparison(token.trim()));
            ors.add(ands.size() == 1 ? ands.get(0) : new Logical(Kind.AND, ands));
        }
        return ors.size() == 1 ? ors.get(0) : new Logical(Kind.OR, ors);
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
        String literal = p[2].replaceAll("^(['\"])(.*)\\1$", "$2");
        if (literal.length() > 256) throw new DomainException("INVALID_CONDITION", "Literal is too long");
        return new Comparison(p[0], op, literal);
    }
}
