package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 组织目录查询入口统一校验分页和筛选，拒绝客户端指定租户。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationQuery(String afterId, int limit, UUID personId, OrganizationUnit.Kind kind, Long beforeRevision) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    /** 每种目录明确允许自己的查询字段，非法或超长游标不进入仓储。 */
    public static OrganizationQuery parse(Map<String, String> query, Set<String> filters) {
        try {
            if (query.keySet().stream().anyMatch(key -> !filters.contains(key))) throw new IllegalArgumentException();
            String after = query.getOrDefault("afterId", "");
            if (!after.isEmpty() && !UUID.fromString(after).toString().equals(after)) throw new IllegalArgumentException();
            String rawLimit = query.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT));
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
            int limit = Integer.parseInt(rawLimit);
            if (limit > MAX_LIMIT) throw new IllegalArgumentException();
            UUID person = query.containsKey("personId") ? UUID.fromString(query.get("personId")) : null;
            var kind = query.containsKey("kind") ? OrganizationUnit.Kind.valueOf(query.get("kind")) : null;
            String before = query.get("beforeRevision");
            if (before != null && !before.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return new OrganizationQuery(after, limit, person, kind, before == null ? null : Long.valueOf(before));
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    /** 组织单元目录必须声明类型，不因字段缺失返回混合类型数据。 */
    public OrganizationUnit.Kind requiredKind() { if (kind == null) throw invalid(); return kind; }
    private static DomainException invalid() { return new DomainException("INVALID_ORGANIZATION_QUERY", "Organization query filters or pagination are invalid"); }
}
