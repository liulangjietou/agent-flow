package io.agentflow.finance;

import io.agentflow.procurement.SupplierPayableHoldCommand;
import io.agentflow.procurement.SupplierPayableHoldObservation;
import io.agentflow.procurement.SupplierPayableHoldPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 原 ERP 应付预留适配器；外部必须原子校验余额版本并持久保存同一授权号的结果。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewaySupplierPayableHold implements SupplierPayableHoldPort {
    private final FinanceGatewayClient client;

    /** 沿用固定租户目的地、有界严格传输和事务外调用。 */
    public GatewaySupplierPayableHold(FinanceGatewayClient client) { this.client = client; }

    /** 只有原授权窗口内的新鲜应付证据可产生新预留，重复调用保持编号、内容及摘要。 */
    @Override public FinanceResult<SupplierPayableHoldObservation> reserve(SupplierPayableHoldCommand command) {
        command.requireSendAt(Instant.now());
        return client.reserveSupplierPayable(command.tenantId(), command.targetDigest(), command.id(), new Reserve(command, command.digest()),
                SupplierPayableHoldObservation.class, value -> value.matches(command, false, Instant.now()));
    }

    /** 查询只传授权号和摘要；即使授权过期，仍可查回原预留。 */
    @Override public FinanceResult<SupplierPayableHoldObservation> query(SupplierPayableHoldCommand command) {
        return client.querySupplierPayableHold(command.tenantId(), command.targetDigest(), new Query(command.id(), command.digest()),
                SupplierPayableHoldObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * ERP 以操作类型、租户、授权号和完整摘要识别原子命令。
     * @author owlzhangfq@gmail.com
     */
    private record Reserve(SupplierPayableHoldCommand command, String commandDigest) { }

    /**
     * 查询不附带可供重新创建预留的明细。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID authorizationId, String commandDigest) { }
}
