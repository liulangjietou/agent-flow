package io.agentflow.procurement;

/**
 * 已持久应付事实在原事务内发布，事件不携带新的授权或外发许可。
 * @author owlzhangfq@gmail.com
 */
public final class SupplierPayableChanged {
    private SupplierPayableChanged() { }
    /**
     * 同一读取请求的实际修订。
     * @author owlzhangfq@gmail.com
     */
    public record Review(SupplierPayableReview current) { }
    /**
     * 原预留当前修订与可选的实际安全结束决定。
     * @author owlzhangfq@gmail.com
     */
    public record Hold(SupplierPayableHoldOperation current, SupplierAuthorizationRetirement retirement) { }
}
