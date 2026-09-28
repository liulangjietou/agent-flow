package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/**
 * ERP 期间、科目与凭证协议适配，未配置或未知结果不会产生虚假入账事实。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayAccounting implements AccountingPeriodPort, AccountMappingPort, AccountingVoucherPort {
    private final FinanceGatewayClient client;
    /** 沿用租户固定目标、严格 JSON 和事务外有界传输。 */
    public GatewayAccounting(FinanceGatewayClient client) { this.client = client; }

    /** 已结账日期直接返回关闭原因，不能由 ERP 默默替换为下一期间。 */
    @Override public FinanceResult<OpenPeriod> period(String tenantId, String targetDigest, AccountingPeriodPort.Request request) {
        return client.readAccounting(tenantId, targetDigest, FinanceGatewayClient.Operation.ACCOUNTING_PERIOD, request,
                OpenPeriod.class, value -> value.matches(request, Instant.now()));
    }
    /** 返回映射必须完整匹配原法人、币种和所需用途。 */
    @Override public FinanceResult<Mapping> mapping(String tenantId, String targetDigest, AccountMappingPort.Request request) {
        return client.readAccounting(tenantId, targetDigest, FinanceGatewayClient.Operation.ACCOUNT_MAPPING, request,
                Mapping.class, value -> value.matches(request, Instant.now()));
    }
    /** 过期证据不能继续发起新过账，ERP 还须在过账事务内复核版本。 */
    @Override public FinanceResult<VoucherObservation> post(String targetDigest, VoucherCommand command) {
        command.requireSendAt(Instant.now());
        return client.postVoucher(command.tenantId(), targetDigest, command.id(), new Post(command, command.digest()),
                VoucherObservation.class, value -> value.matches(command, false, Instant.now()));
    }
    /** 即使原科目或期间证据到期，仍可查询之前已受理的原凭证。 */
    @Override public FinanceResult<VoucherObservation> query(String targetDigest, VoucherCommand command) {
        return client.queryVoucher(command.tenantId(), targetDigest, new Query(command.id(), command.digest()),
                VoucherObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * 原始凭证与固定摘要一起传输。
     * @author owlzhangfq@gmail.com
     */
    private record Post(VoucherCommand command, String commandDigest) { }
    /**
     * ERP 按原操作查询，缓存未命中不能冒充权威不存在。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
