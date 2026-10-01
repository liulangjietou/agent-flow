package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static io.agentflow.notification.NotificationDeliveryProgress.*;

/** 本人投递查询投影，不暴露地址、服务器、凭据、租约令牌或内部绑定摘要。
 * @author owlzhangfq@gmail.com
 */
public final class NotificationDeliveryViews {
    private NotificationDeliveryViews() { }

    /** 受理状态与最终送达区分；所有可空字段明确序列化。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(UUID id, UUID inboxId, NotificationChannel channel, Status status, long version, int attempts,
                          int cycleAttempts, FailureCode errorCode, Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil) {
        /** 从原投递读取当前状态，不附带业务正文。 */
        public static Summary of(NotificationDelivery value) {
            var p = value.progress();
            return new Summary(value.id(), value.inboxId(), value.channel(), p.status(), p.version(), p.attempts(), p.cycleAttempts(),
                    p.errorCode(), value.createdAt(), p.changedAt(), p.nextAttemptAt(), p.leaseUntil());
        }
    }
    /** 原投递的不可变状态历史；原因和操作人只向本人返回。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Event(long version, Status status, int attempts, int cycleAttempts, FailureCode errorCode, String actor, String reason, Instant occurredAt) { }
    /** 此项只表示读取时的恢复资格，实际写入仍重新加锁核对。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RetryEligibility(boolean allowed, boolean requiresDuplicateAcknowledgement, String blockedCode) { }
    /** 投递分页不把本页数量当作全部数量。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<Summary> items, String nextCursor) { }
    /** 历史页明确保留下一页游标，包括空值。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HistoryPage(List<Event> items, String nextCursor) { }
    /** 详情、读取时恢复资格和与当前版本一致的首段历史。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(Summary delivery, RetryEligibility retry, HistoryPage history) { }
}
