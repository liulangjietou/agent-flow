package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ERP 独立冲销写协议；原件、反向分录及摘要一次发送，超时后只查询原操作。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayAccountingReversal implements AccountingReversalPort {
    private final FinanceGatewayClient client;
    /** 复用固定租户目的地及严格有界传输，不新增自动重试。 */
    public GatewayAccountingReversal(FinanceGatewayClient client) { this.client = client; }
    /** 新写入必须仍在授权窗口内，ERP 对原凭证和会计期间做原子版本检查。 */
    @Override public FinanceResult<VoucherReversalObservation> post(String targetDigest, VoucherReversalCommand command) {
        command.requireSendAt(Instant.now());
        return client.postVoucherReversal(command.source().command().tenantId(), targetDigest, command.id(), new Post(command, command.lines(), command.digest()),
                VoucherReversalObservation.class, value -> value.matches(command, false, Instant.now()));
    }
    /** 原命令过期不阻止对账，查询不包含幂等写入头。 */
    @Override public FinanceResult<VoucherReversalObservation> query(String targetDigest, VoucherReversalCommand command) {
        return client.queryVoucherReversalOperation(command.source().command().tenantId(), targetDigest, new Query(command.id(), command.digest()),
                VoucherReversalObservation.class, value -> value.matches(command, true, Instant.now()));
    }
    /**
     * 分录显式传输供 ERP 校验，字段全部由服务端历史命令派生。
     * @author owlzhangfq@gmail.com
     */
    private record Post(VoucherReversalCommand command, List<VoucherReversalCommand.Line> lines, String commandDigest) { }
    /**
     * 原编号与摘要确定同一次不可替换的冲销请求。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
