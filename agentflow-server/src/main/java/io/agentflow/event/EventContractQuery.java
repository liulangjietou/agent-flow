package io.agentflow.event;

import io.agentflow.common.DomainException;
import java.util.Map;
import java.util.Set;

/**
 * 管理页和设计器共用有界游标，调用方不能通过筛选参数指定其他租户。
 * @author owlzhangfq@gmail.com
 */
public record EventContractQuery(String afterKey, Long before, int limit) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    /** 当前目录按稳定业务键翻页。 */
    public static EventContractQuery directory(Map<String, String> query) {
        requireKeys(query, Set.of("afterKey", "limit"));
        String after = query.get("afterKey");
        if (after != null && !after.matches("[a-z][a-z0-9._-]{0,63}")) throw invalid();
        return new EventContractQuery(after, null, limit(query));
    }

    /** 发布版本和启停历史各自使用明确的倒序游标。 */
    public static EventContractQuery versions(Map<String, String> query, boolean history) {
        String key = history ? "beforeRevision" : "beforeVersion";
        requireKeys(query, Set.of(key, "limit"));
        return new EventContractQuery(null, query.containsKey(key) ? positive(query.get(key)) : null, limit(query));
    }

    /** 指定版本的读取不接受零或负值，也不自动解析成最新版。 */
    public static void requireVersion(long version) { if (version < 1) throw invalid(); }

    private static int limit(Map<String, String> query) {
        long value = query.containsKey("limit") ? positive(query.get("limit")) : DEFAULT_LIMIT;
        if (value > MAX_LIMIT) throw invalid();
        return (int) value;
    }

    private static long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw invalid(); }
    }

    private static void requireKeys(Map<String, String> query, Set<String> allowed) { if (!allowed.containsAll(query.keySet())) throw invalid(); }
    private static DomainException invalid() { return new DomainException("INVALID_EVENT_CONTRACT_QUERY", "Invalid event contract query"); }
}
