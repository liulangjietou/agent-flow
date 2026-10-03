package io.agentflow.organization;

import io.agentflow.common.DomainException;

/**
 * 同一组织上下文内实体修改共用乐观修订约束。
 * @author owlzhangfq@gmail.com
 */
final class OrganizationRevision {
    private OrganizationRevision() { }
    static void require(long actual, long expected) {
        if (actual != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Organization record revision has changed");
    }
}
