package io.agentflow.common;

import java.util.Set;

/**
 * 当前请求的认证主体。租户和角色来自服务端认证上下文。
 * @author owlzhangfq@gmail.com
 */
public record Actor(String tenantId, String userId, Set<String> roles) {
    /** 创建不可变主体。 */
    public Actor {
        if (org.apache.commons.lang3.StringUtils.isBlank(tenantId)
                || org.apache.commons.lang3.StringUtils.isBlank(userId)) {
            throw new DomainException("INVALID_ACTOR", "Tenant and user are required");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** 判断主体是否拥有角色。 */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** 要求主体拥有角色，否则拒绝当前用例。 */
    public void requireRole(String role) {
        if (!hasRole(role)) {
            throw new DomainException("FORBIDDEN", "Required role is missing");
        }
    }
}
