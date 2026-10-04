package io.agentflow.notification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立冲销消息键只接受规范身份与闭集事实，不能转成任意业务入口。
 * @author owlzhangfq@gmail.com
 */
class ReversalNoticeTest {
    @Test void supportedFactsKeepTheOriginalCommandIdentity() {
        UUID id = UUID.randomUUID();
        for (var notice : ReversalNotice.values()) {
            assertThat(ReversalNotice.source(notice.eventKey(id))).contains(new ReversalNotice.Source(id, notice));
            assertThat(notice.eventKey(id)).hasSizeLessThan(128);
        }
    }
    @Test void malformedOrForeignSourcesAreRejected() {
        UUID id = UUID.fromString("abcdefab-1234-1234-1234-123456789abc");
        for (String key : new String[]{"", "voucher:" + id + ":POSTED", "reversal:1-1-1-1-1:POSTED", "reversal:" + id.toString().toUpperCase() + ":POSTED",
                "reversal:" + id + ":UNKNOWN:extra", "reversal:" + id + ":PENDING", "reversal:" + id + ":"}) assertThat(ReversalNotice.source(key)).isEmpty();
        assertThat(ReversalNotice.source(null)).isEmpty();
    }
}
