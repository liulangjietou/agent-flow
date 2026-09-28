package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/**
 * 支付协议适配器只接受与原授权精确匹配的到账事实，未知结果继续由原交易查询恢复。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayPaymentSystem implements PaymentSystemPort {
    private final FinanceGatewayClient client;

    /** 支付使用现有固定目的地和有界传输，不允许调用方提供任意银行接口地址。 */
    public GatewayPaymentSystem(FinanceGatewayClient client) { this.client = client; }

    /** 过期授权不再外发，但不伪造外部交易失败；本地编排另行处理未发送状态。 */
    @Override public FinanceResult<PaymentObservation> execute(String targetDigest, PaymentCommand command) {
        command.requireSendAt(Instant.now());
        return client.executePayment(command.tenantId(), targetDigest, command.id(), new Execute(command, command.digest()),
                PaymentObservation.class, value -> value.matches(command, false, Instant.now()));
    }

    /** 查询只携带授权号与命令摘要，不向资金系统重复发送完整付款指令。 */
    @Override public FinanceResult<PaymentObservation> query(String targetDigest, PaymentCommand command) {
        return client.queryPayment(command.tenantId(), targetDigest, new Query(command.id(), command.digest()),
                PaymentObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * 资金系统按授权号和摘要持久化幂等结果，摘要不同的重复编号必须拒绝。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(PaymentCommand command, String commandDigest) { }

    /**
     * 传输请求号可以更新，业务查询对象仍为原付款授权。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID authorizationId, String commandDigest) { }
}
