package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.notification.NotificationDeliveryViews.*;

/** 本人投递与历史的只读用例，不根据管理员角色放宽接收人范围。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(readOnly = true)
public class NotificationDeliveryQueries {
    private final JdbcNotificationDeliveryStore store;
    private final NotificationDeliveryService deliveries;

    /** 读取持久事实及当前恢复资格，不发起发送或重试。 */
    public NotificationDeliveryQueries(JdbcNotificationDeliveryStore store, NotificationDeliveryService deliveries) {
        this.store = store; this.deliveries = deliveries;
    }

    /** 受过滤的列表返回明确的继续游标，不查询全部记录。 */
    public Page list(Actor actor, NotificationDeliveryQueryParameters.Search query) {
        var values = store.search(actor, query); int count = Math.min(query.limit(), values.size());
        var items = values.subList(0, count).stream().map(Summary::of).toList();
        return new Page(items, values.size() > count ? query.cursor(values.get(count - 1)) : null);
    }

    /** 首段历史限制在详情版本以内，后台并发确认不会造成版本前后倒置。 */
    public Detail detail(Actor actor, UUID id, NotificationDeliveryQueryParameters.History query) {
        var value = own(actor, id);
        return new Detail(Summary.of(value), deliveries.retryEligibility(value), historyPage(actor, value, query));
    }

    /** 每次历史翻页重新验证本人归属，不能借别人的投递标识读取原因。 */
    public HistoryPage history(Actor actor, UUID id, NotificationDeliveryQueryParameters.History query) {
        return historyPage(actor, own(actor, id), query);
    }

    /** 原键回放前仍检查当前归属，不要求投递继续处于可重试状态。 */
    public void requireOwner(Actor actor, UUID id) { own(actor, id); }

    private HistoryPage historyPage(Actor actor, NotificationDelivery value, NotificationDeliveryQueryParameters.History query) {
        var rows = store.history(actor, value.id(), value.progress().version(), query); int count = Math.min(query.limit(), rows.size());
        return new HistoryPage(List.copyOf(rows.subList(0, count)), rows.size() > count ? query.cursor(rows.get(count - 1).version()) : null);
    }
    private NotificationDelivery own(Actor actor, UUID id) {
        return store.get(actor, id).orElseThrow(() -> new DomainException("NOTIFICATION_DELIVERY_NOT_FOUND", "Notification delivery not found"));
    }
}
