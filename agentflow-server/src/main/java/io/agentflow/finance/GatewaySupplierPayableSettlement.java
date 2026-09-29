package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.procurement.SupplierPayableSettlementCommand;
import io.agentflow.procurement.SupplierPayableSettlementEvidence;
import io.agentflow.procurement.SupplierPayableSettlementObservation;
import io.agentflow.procurement.SupplierPayableSettlementPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ERP 原应付结算协议；只向原财务目的地发送固定命令，不传可变的前端成功声明。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewaySupplierPayableSettlement implements SupplierPayableSettlementPort {
    private final FinanceGatewayClient client;
    /** 复用事务外、有界严格 JSON 和固定财务目标的传输。 */
    public GatewaySupplierPayableSettlement(FinanceGatewayClient client) { this.client = client; }

    /** 新鲜复查只作发送门槛，外发原文不随重试改变；ERP 在一次事务内去重、核销及记账。 */
    @Override public FinanceResult<SupplierPayableSettlementObservation> settle(SupplierPayableSettlementCommand command, SupplierPayableSettlementEvidence evidence) {
        if (evidence == null || !evidence.matches(command, Instant.now())) {
            throw new DomainException("SUPPLIER_PAYABLE_SETTLEMENT_EVIDENCE_CHANGED", "Fresh original payable hold, bank receipt and accounting period are required before settlement");
        }
        return client.settleSupplierPayable(command.tenantId(), command.targetDigest(), command.id(), new Execute(command, command.digest()),
                SupplierPayableSettlementObservation.class, value -> command.matches(value, false, Instant.now()));
    }

    /** 查回未知结算只携带原业务号及摘要，不附带可再次核销的指令或写入幂等头。 */
    @Override public FinanceResult<SupplierPayableSettlementObservation> query(SupplierPayableSettlementCommand command) {
        return client.querySupplierPayableSettlement(command.tenantId(), command.targetDigest(), new Query(command.id(), command.digest()),
                SupplierPayableSettlementObservation.class, value -> command.matches(value, true, Instant.now()));
    }

    /**
     * 固定原银行事实、原预留、财务身份和记账意图，精确数量随完整批准来源传输。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(SupplierPayableSettlementCommand command, String commandDigest) { }
    /**
     * 每次传输关联号可更新，结算业务身份不变。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
