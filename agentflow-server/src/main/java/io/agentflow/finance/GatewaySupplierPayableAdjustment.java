package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.procurement.SupplierPayableAdjustmentCommand;
import io.agentflow.procurement.SupplierPayableAdjustmentEvidence;
import io.agentflow.procurement.SupplierPayableAdjustmentObservation;
import io.agentflow.procurement.SupplierPayableAdjustmentPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 供应商回款账务调整只访问原财务目标，资金原件与原子结果均按固定协议核对。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewaySupplierPayableAdjustment implements SupplierPayableAdjustmentPort {
    private final FinanceGatewayClient client;
    /** 网络调用复用事务外传输、严格 JSON、响应大小限制和禁止自动重试规则。 */
    public GatewaySupplierPayableAdjustment(FinanceGatewayClient client) { this.client = client; }

    /** 新鲜查询只决定能否发送，不改变原授权的命令字节和外部幂等键。 */
    @Override public FinanceResult<SupplierPayableAdjustmentObservation> adjust(SupplierPayableAdjustmentCommand command, SupplierPayableAdjustmentEvidence evidence) {
        if (evidence == null || !evidence.matches(command, Instant.now())) {
            throw new DomainException("SUPPLIER_PAYABLE_ADJUSTMENT_EVIDENCE_CHANGED", "Fresh bank returns and original ERP accounting facts are required before adjustment");
        }
        return client.adjustSupplierPayable(command.tenantId(), command.targetDigest(), command.id(), new Execute(command, command.digest()),
                SupplierPayableAdjustmentObservation.class, value -> command.matches(value, false, Instant.now()));
    }

    /** 查询只携带原号和摘要，即使原授权或会计期间已经到期也继续找回账务事实。 */
    @Override public FinanceResult<SupplierPayableAdjustmentObservation> query(SupplierPayableAdjustmentCommand command) {
        return client.querySupplierPayableAdjustment(command.tenantId(), command.targetDigest(), new Query(command.id(), command.digest()),
                SupplierPayableAdjustmentObservation.class, value -> command.matches(value, true, Instant.now()));
    }

    /**
     * 写入传输完整固定来源，外部按原号、摘要、原付款和逐笔银行入款联合防重。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(SupplierPayableAdjustmentCommand command, String commandDigest) { }
    /**
     * 原调整号只读恢复，不带可重复执行的指令。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
