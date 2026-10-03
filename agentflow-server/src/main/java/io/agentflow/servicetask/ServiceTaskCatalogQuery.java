package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import org.springframework.util.MultiValueMap;

import java.util.Set;

/**
 * 目录入口统一验证有界游标与唯一参数，租户只取认证上下文。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskCatalogQuery(String afterKey, Long beforeVersion, int limit) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    /** 最新版目录按键升序，不因停用而自动降级为旧版。 */
    public static ServiceTaskCatalogQuery directory(MultiValueMap<String, String> query) {
        requireKeys(query, Set.of("afterKey", "limit"));
        String after = query.getFirst("afterKey");
        if (after != null) requireKey(after);
        return new ServiceTaskCatalogQuery(after, null, limit(query));
    }

    /** 历史版本游标按完整 long 解析，返回 JSON 时保持十进制文本以免浏览器丢失精度。 */
    public static ServiceTaskCatalogQuery versions(MultiValueMap<String, String> query) {
        requireKeys(query, Set.of("beforeVersion", "limit"));
        return new ServiceTaskCatalogQuery(null, query.containsKey("beforeVersion") ? version(query.getFirst("beforeVersion")) : null, limit(query));
    }

    /** 路径标识使用与可信操作声明相同的字面量规则。 */
    public static void requireKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9._-]{0,63}")) throw invalid();
    }

    /** 原版本必须显式给出，不能用 latest、前导零或整数溢出代替。 */
    public static long version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw invalid(); }
    }

    private static int limit(MultiValueMap<String, String> query) {
        long value = query.containsKey("limit") ? version(query.getFirst("limit")) : DEFAULT_LIMIT;
        if (value > MAX_LIMIT) throw invalid();
        return (int) value;
    }

    private static void requireKeys(MultiValueMap<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
    }

    private static DomainException invalid() { return new DomainException("INVALID_SERVICE_TASK_QUERY", "Invalid service task catalog query"); }
}
