package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import java.util.Map;
import java.util.Set;

/**
 * 管理页与设计器共用的有界日历分页入口，禁止调用方指定租户。
 * @author owlzhangfq@gmail.com
 */
public record CalendarQuery(String afterKey, Long beforeRevision, int limit) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    /** 目录按业务键翻页，只接受目录参数。 */
    public static CalendarQuery directory(Map<String, String> values) {
        requireKeys(values, Set.of("afterKey", "limit"));
        String after = values.get("afterKey");
        if (after != null && (after.isBlank() || after.length() > 64)) throw invalid();
        return new CalendarQuery(after, null, limit(values));
    }

    /** 历史按修订倒序翻页，只接受历史参数。 */
    public static CalendarQuery versions(Map<String, String> values) {
        requireKeys(values, Set.of("beforeRevision", "limit"));
        return new CalendarQuery(null, values.containsKey("beforeRevision") ? positive(values.get("beforeRevision")) : null, limit(values));
    }

    /** 路径中的修订也遵守同一个正整数边界。 */
    public static void requireRevision(long revision) { if (revision < 1) throw invalid(); }

    private static void requireKeys(Map<String, String> values, Set<String> allowed) { if (!allowed.containsAll(values.keySet())) throw invalid(); }
    private static long positive(String value) {
        try { long parsed = Long.parseLong(value); requireRevision(parsed); return parsed; }
        catch (NumberFormatException exception) { throw invalid(); }
    }
    private static int limit(Map<String, String> values) {
        long limit = values.containsKey("limit") ? positive(values.get("limit")) : DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) throw invalid();
        return (int) limit;
    }
    private static DomainException invalid() { return new DomainException("INVALID_CALENDAR_QUERY", "Invalid calendar pagination query"); }
}
