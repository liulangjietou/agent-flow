package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

/**
 * 科目配置入口只接受声明过的筛选与分页参数，明确区分范围和业务键。
 * @author owlzhangfq@gmail.com
 */
public final class AccountMappingConfigurationQuery {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private AccountMappingConfigurationQuery() { }

    /** 写入和单版本正文不能通过查询参数更换租户或范围。 */
    public static void none(MultiValueMap<String, String> parameters) { require(parameters, Set.of()); }

    /** 目录可按法人和币种分别筛选，分页游标仍为稳定业务键。 */
    public static Directory directory(MultiValueMap<String, String> parameters) {
        require(parameters, Set.of("legalEntityId", "currency", "afterKey", "limit"));
        var entity = parameters.getFirst("legalEntityId"); var currency = parameters.getFirst("currency"); var key = parameters.getFirst("afterKey");
        if (key != null) key(key);
        return new Directory(entity == null ? null : entity(entity), currency == null ? null : currency(currency), key, limit(parameters));
    }

    /** 当前生效配置必须明确法人和币种，不假定默认范围。 */
    public static Scope scope(MultiValueMap<String, String> parameters) {
        require(parameters, Set.of("legalEntityId", "currency")); return scopeValues(parameters);
    }

    /** 生效历史使用同一法人币种范围的倒序游标。 */
    public static ScopedHistory activations(MultiValueMap<String, String> parameters) {
        require(parameters, Set.of("legalEntityId", "currency", "beforeVersion", "limit"));
        return new ScopedHistory(scopeValues(parameters), before(parameters), limit(parameters));
    }

    /** 单配置的发布历史不接受另一法人筛选来混淆身份。 */
    public static History history(MultiValueMap<String, String> parameters) {
        require(parameters, Set.of("beforeVersion", "limit")); return new History(before(parameters), limit(parameters));
    }

    /** 业务键使用统一稳定的小写格式。 */
    public static void key(String value) { if (!AccountMappingDraft.validKey(value)) throw invalid(); }

    /** 正版本不接受前导零、符号或整数溢出。 */
    public static long version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException malformed) { throw invalid(); }
    }

    private static Scope scopeValues(MultiValueMap<String, String> parameters) { return new Scope(entity(parameters.getFirst("legalEntityId")), currency(parameters.getFirst("currency"))); }
    private static UUID entity(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return UUID.fromString(value);
    }
    private static String currency(String value) {
        try { Money.zero(value); return value; } catch (DomainException malformed) { throw invalid(); }
    }
    private static long before(MultiValueMap<String, String> parameters) { var value = parameters.getFirst("beforeVersion"); return value == null ? Long.MAX_VALUE : version(value); }
    private static int limit(MultiValueMap<String, String> parameters) {
        var value = parameters.getFirst("limit"); if (value == null) return DEFAULT_LIMIT;
        long parsed = version(value); if (parsed > MAX_LIMIT) throw invalid(); return (int) parsed;
    }
    private static void require(MultiValueMap<String, String> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet()) || parameters.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
    }
    private static DomainException invalid() { return new DomainException("INVALID_ACCOUNT_MAPPING_QUERY", "Account mapping query, scope or identifier is invalid"); }

    /**
     * 目录筛选与稳定游标。
     * @author owlzhangfq@gmail.com
     */
    public record Directory(UUID legalEntityId, String currency, String afterKey, int limit) { }
    /**
     * 独立配置范围。
     * @author owlzhangfq@gmail.com
     */
    public record Scope(UUID legalEntityId, String currency) { }
    /**
     * 发布版本分页。
     * @author owlzhangfq@gmail.com
     */
    public record History(long beforeVersion, int limit) { }
    /**
     * 生效历史范围与分页。
     * @author owlzhangfq@gmail.com
     */
    public record ScopedHistory(Scope scope, long beforeVersion, int limit) { }
}
