package io.agentflow.notification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原预算消息键只接受规范身份与闭集事实，不能转成任意业务入口。
 * @author owlzhangfq@gmail.com
 */
class BudgetNoticeTest {
    @Test void supportedFactsKeepTheOriginalCommandIdentity() {
        UUID id = UUID.randomUUID();
        for (var notice : BudgetNotice.values()) {
            assertThat(BudgetNotice.source(notice.eventKey(id))).contains(new BudgetNotice.Source(id, notice));
            assertThat(notice.eventKey(id)).hasSizeLessThan(128);
        }
    }
    @Test void malformedOrForeignSourcesAreRejected() {
        UUID id = UUID.fromString("abcdefab-1234-1234-1234-123456789abc");
        for (String key : new String[]{"", "voucher:" + id + ":APPLIED", "budget:1-1-1-1-1:APPLIED", "budget:" + id.toString().toUpperCase() + ":APPLIED",
                "budget:" + id + ":UNKNOWN:extra", "budget:" + id + ":PENDING", "budget:" + id + ":"}) assertThat(BudgetNotice.source(key)).isEmpty();
        assertThat(BudgetNotice.source(null)).isEmpty();
    }
}
