package io.agentflow.finance;

import io.agentflow.procurement.SupplierPaymentCommand;
import io.agentflow.procurement.SupplierPaymentPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 供应商银行协议只接受原预留支持的不可变指令，与员工付款使用不同外部操作。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewaySupplierPayment implements SupplierPaymentPort {
    private final FinanceGatewayClient client;

    /** 共用固定目的地、有界严格传输及事务外执行约束。 */
    public GatewaySupplierPayment(FinanceGatewayClient client) { this.client = client; }

    /** 银行适配层按原编号及摘要原子去重，超时不重发；金额或账户不符不能记为到账。 */
    @Override public FinanceResult<PaymentObservation> execute(SupplierPaymentCommand command) {
        command.requireSendAt(Instant.now());
        return client.executeSupplierPayment(command.tenantId(), command.targetDigest(), command.id(), new Execute(command, command.digest()),
                PaymentObservation.class, value -> command.matches(value, false, Instant.now()));
    }

    /** 查询只携带原授权编号和摘要，不附带能够创建另一笔付款的指令。 */
    @Override public FinanceResult<PaymentObservation> query(SupplierPaymentCommand command) {
        return client.querySupplierPayment(command.tenantId(), command.targetDigest(), new Query(command.id(), command.digest()),
                PaymentObservation.class, value -> command.matches(value, true, Instant.now()));
    }

    /**
     * 原预留事实、批准内容与出纳选择均在固定命令内，由银行适配器按合同核验。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(SupplierPaymentCommand command, String commandDigest) { }

    /**
     * 传输关联号可更新，银行业务查询号永远不变。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID authorizationId, String commandDigest) { }
}
