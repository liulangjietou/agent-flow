package io.agentflow.definition;

import java.util.ArrayList;
import java.util.List;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 有界递归下降解析器：只构造领域 AST，不执行脚本、函数或对象属性访问。
 * @author owlzhangfq@gmail.com
 */
final class ConditionV2Parser {
    private static final int MAX_LENGTH = 4000;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_COMPARISONS = 100;
    private static final int MAX_MEMBERS = 50;
    private static final int MAX_LITERAL = 256;
    private final String source;
    private int cursor;
    private int comparisons;

    ConditionV2Parser(String source) { this.source = source == null ? "" : source; }

    ConditionAst parse() {
        if (source.length() > MAX_LENGTH) throw error("Condition is too long");
        space();
        if (end()) return new Logical(Kind.AND, List.of());
        ConditionAst result = or(0);
        space();
        if (!end()) throw error("Unexpected token");
        return result;
    }

    private ConditionAst or(int depth) {
        List<ConditionAst> terms = new ArrayList<>();
        terms.add(and(depth));
        while (symbol("||") || word("OR")) terms.add(and(depth));
        return terms.size() == 1 ? terms.get(0) : new Logical(Kind.OR, terms);
    }

    private ConditionAst and(int depth) {
        List<ConditionAst> terms = new ArrayList<>();
        terms.add(unary(depth));
        while (symbol("&&") || word("AND")) terms.add(unary(depth));
        return terms.size() == 1 ? terms.get(0) : new Logical(Kind.AND, terms);
    }

    private ConditionAst unary(int depth) {
        space();
        if (depth > MAX_DEPTH) throw error("Condition nesting limit exceeded");
        if (symbol("!")) return new Negation(unary(depth + 1));
        if (symbol("(")) {
            ConditionAst nested = or(depth + 1);
            if (!symbol(")")) throw error("Closing parenthesis is required");
            return nested;
        }
        return comparison();
    }

    private ConditionAst comparison() {
        if (++comparisons > MAX_COMPARISONS) throw error("Too many comparisons");
        String field = field();
        if (word("NOT_EXISTS")) return new Comparison(field, Operator.NOT_EXISTS, "");
        if (word("EXISTS")) return new Comparison(field, Operator.EXISTS, "");
        if (word("IN")) {
            if (!symbol("[")) throw error("Membership list is required");
            List<String> values = new ArrayList<>();
            do {
                space();
                if (end() || source.charAt(cursor) != '"' && source.charAt(cursor) != '\'') {
                    throw error("Membership values must be quoted");
                }
                values.add(literal());
                if (values.size() > MAX_MEMBERS) throw error("Too many membership values");
            } while (symbol(","));
            if (!symbol("]")) throw error("Closing bracket is required");
            return new Membership(field, values);
        }
        Operator operator;
        if (symbol("==")) operator = Operator.EQ;
        else if (symbol("!=")) operator = Operator.NE;
        else if (symbol(">=")) operator = Operator.GE;
        else if (symbol("<=")) operator = Operator.LE;
        else if (symbol(">")) operator = Operator.GT;
        else if (symbol("<")) operator = Operator.LT;
        else throw error("Comparison operator is required");
        return new Comparison(field, operator, literal());
    }

    private String field() {
        space();
        int start = cursor;
        while (!end() && identifier(source.charAt(cursor))) cursor++;
        String field = source.substring(start, cursor);
        if (!field.matches("[a-zA-Z][a-zA-Z0-9_.]{0,63}")) throw error("Invalid field identifier");
        return field;
    }

    private String literal() {
        space();
        if (end()) throw error("Literal is required");
        char first = source.charAt(cursor);
        String value;
        if (first == '\'' || first == '"') {
            cursor++;
            StringBuilder text = new StringBuilder();
            boolean closed = false;
            while (!end()) {
                char current = source.charAt(cursor++);
                if (current == first) { closed = true; break; }
                if (current < ' ') throw error("Control characters must be escaped");
                text.append(current == '\\' ? escaped() : current);
                if (text.length() > MAX_LITERAL) throw error("Literal is too long");
            }
            if (!closed) throw error("Quoted literal is not closed");
            value = text.toString();
        } else {
            int start = cursor;
            while (!end() && !Character.isWhitespace(source.charAt(cursor)) && ")]&,|".indexOf(source.charAt(cursor)) < 0) cursor++;
            value = source.substring(start, cursor);
            if (!value.matches("-?\\d+(\\.\\d+)?") && !value.equals("true") && !value.equals("false")) {
                throw error("Text literals must be quoted");
            }
        }
        if (value.length() > MAX_LITERAL || value.contains(";") || value.contains("$" + "{") || value.contains("#" + "{")) {
            throw error("Literal contains unsupported content");
        }
        return value;
    }

    private char escaped() {
        if (end()) throw error("Incomplete escape sequence");
        return switch (source.charAt(cursor++)) {
            case '"' -> '"'; case '\'' -> '\''; case '\\' -> '\\'; case '/' -> '/';
            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f';
            case 'u' -> {
                if (cursor + 4 > source.length()) throw error("Incomplete Unicode escape");
                String hex = source.substring(cursor, cursor + 4);
                if (!hex.matches("[0-9a-fA-F]{4}")) throw error("Invalid Unicode escape");
                cursor += 4;
                yield (char) Integer.parseInt(hex, 16);
            }
            default -> throw error("Unsupported escape sequence");
        };
    }

    private boolean symbol(String token) {
        space();
        if (!source.startsWith(token, cursor)) return false;
        cursor += token.length();
        return true;
    }

    private boolean word(String token) {
        space();
        int after = cursor + token.length();
        if (!source.regionMatches(true, cursor, token, 0, token.length())
                || after < source.length() && identifier(source.charAt(after))) return false;
        cursor = after;
        return true;
    }

    private boolean identifier(char value) { return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9' || value == '_' || value == '.'; }
    private void space() { while (!end() && Character.isWhitespace(source.charAt(cursor))) cursor++; }
    private boolean end() { return cursor >= source.length(); }
    private ConditionSyntaxException error(String message) { return new ConditionSyntaxException(cursor + 1, message); }
}
