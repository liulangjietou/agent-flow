package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/**
 * 配置入口统一限定查询范围，重复参数或伪造租户不能被静默忽略。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseConfigurationQuery {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private ExpenseConfigurationQuery() { }

    /** 单资源读取和写入都不接受查询参数。 */
    public static void none(MultiValueMap<String, String> parameters) { require(parameters, Set.of()); }

    /** 制度目录按稳定业务键向后分页。 */
    public static Directory directory(MultiValueMap<String, String> parameters) {
        require(parameters, Set.of("afterKey", "limit"));
        String key = parameters.getFirst("afterKey"); if (key != null) key(key);
        return new Directory(key, limit(parameters));
    }

    /** 所有历史集合按正版本倒序分页。 */
    public static History history(MultiValueMap<String, String> parameters) {
        require(parameters, Set.of("beforeVersion", "limit"));
        var value = parameters.getFirst("beforeVersion");
        return new History(value == null ? Long.MAX_VALUE : version(value), limit(parameters));
    }

    /** 路径键只接受已声明的业务标识格式。 */
    public static void key(String value) { if (!ExpensePolicyDraft.validKey(value)) throw invalid(); }

    /** 路径和分页版本不接受负数、前导符号、前导零或溢出。 */
    public static long version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException malformed) { throw invalid(); }
    }

    private static int limit(MultiValueMap<String, String> parameters) {
        String value = parameters.getFirst("limit"); if (value == null) return DEFAULT_LIMIT;
        long parsed = version(value); if (parsed > MAX_LIMIT) throw invalid();
        return (int) parsed;
    }
    private static void require(MultiValueMap<String, String> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet()) || parameters.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_CONFIGURATION_QUERY", "Expense configuration query or identifier is invalid"); }

    /**
     * 制度目录分页参数。
     * @author owlzhangfq@gmail.com
     */
    public record Directory(String afterKey, int limit) { }
    /**
     * 不可变历史分页参数。
     * @author owlzhangfq@gmail.com
     */
    public record History(long beforeVersion, int limit) { }
}
