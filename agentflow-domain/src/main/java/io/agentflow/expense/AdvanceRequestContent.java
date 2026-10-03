package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 员工借款用途、金额与承诺归还日；收款账户只从财务主数据取得。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRequestContent(UUID legalEntityId, String title, String purpose, Money amount, LocalDate dueOn) {
    /** 草稿保存完整借款约定，归还日是否有效按提交时法人的本地日期判断。 */
    public AdvanceRequestContent {
        if (legalEntityId == null || StringUtils.isBlank(title) || title.length() > 256
                || StringUtils.isBlank(purpose) || purpose.length() > 2000 || amount == null
                || amount.value().signum() <= 0 || dueOn == null) {
            throw new DomainException("INVALID_ADVANCE_REQUEST", "Advance request requires a legal entity, purpose, positive amount and repayment date");
        }
        title = title.trim(); purpose = purpose.trim();
    }

    /** 账户与批准状态只能由服务端事实产生，未知字段不能静默忽略。 */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown advance request content field"); }
}
