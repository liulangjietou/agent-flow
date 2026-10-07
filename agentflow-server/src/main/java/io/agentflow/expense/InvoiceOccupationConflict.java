package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.util.List;

/**
 * 原提交事务回滚后的冲突响应；实时可见信息仅用于当前响应，不进入幂等回执和预检历史。
 * @author owlzhangfq@gmail.com
 */
public final class InvoiceOccupationConflict extends DomainException {
    private static final String OCCUPIED = "INVOICE_OCCUPIED";
    private final List<InvoiceOccupationQueries.Conflict> conflicts;

    /** 保留已有公开错误码与原始原因，以 409 说明本次资源竞争。 */
    public InvoiceOccupationConflict(DomainException failure, List<InvoiceOccupationQueries.Conflict> conflicts) {
        super(failure.code(), failure.getMessage()); initCause(failure); this.conflicts = List.copyOf(conflicts);
    }

    /** 只有已确认的票号占用冲突可以附加当前授权来源。 */
    public static boolean matches(DomainException failure) {
        return OCCUPIED.equals(failure.code()) || "RESOURCES_CHANGED".equals(failure.code())
                && failure.getCause() instanceof DomainException original && OCCUPIED.equals(original.code());
    }

    /** 当前响应的最小冲突详情。 */
    public List<InvoiceOccupationQueries.Conflict> conflicts() { return conflicts; }
}
