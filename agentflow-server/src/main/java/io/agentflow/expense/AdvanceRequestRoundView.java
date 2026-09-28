package io.agentflow.expense;

import io.agentflow.finance.FinanceCatalog;
import java.time.Instant;

/**
 * 页面可读取的冻结借款约定；不包含供支付端口使用的账户引用和摘要。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRequestRoundView(int roundNo, long submittedRequestVersion, String submittedBy, Instant submittedAt,
                                      AdvanceRequestContent content, FinanceCatalog.LegalEntity legalEntity,
                                      String catalogVersion, String maskedAccount) {
    /** 预检预览与审批详情共用同一脱敏边界。 */
    public static AdvanceRequestRoundView of(AdvanceRequestRound round) {
        return new AdvanceRequestRoundView(round.roundNo(), round.submittedRequestVersion(), round.submittedBy(), round.submittedAt(),
                round.content(), round.legalEntity(), round.catalogVersion(), round.account().maskedAccount());
    }
}
