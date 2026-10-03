package io.agentflow.notification;

import io.agentflow.auth.AuthService;
import io.agentflow.approval.process.FlowableApprovalProxyNotifications;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.notification.NotificationDeliveryProgress.*;

/** 跨组织、偏好和投递的短事务编排，外部传输由事务外后台调用。
 * @author owlzhangfq@gmail.com
 */
@Service
public class NotificationDeliveryService {
    private final JdbcNotificationDeliveryStore store;
    private final NotificationPreferencesRepository preferences;
    private final NotificationDestinations destinations;
    private final OrganizationRepository organization;
    private final AuthService demo;
    private final FlowableApprovalProxyNotifications proxies;
    private final PaymentNotificationAccess payments;
    private final SupplierPaymentNotificationAccess supplierPayments;
    private final VoucherNotificationAccess vouchers;
    private final BudgetNotificationAccess budgets;
    private final ReversalNotificationAccess reversals;

    /** 组织、偏好、投递按固定顺序加锁，不能与关闭偏好的顺序反转。 */
    public NotificationDeliveryService(JdbcNotificationDeliveryStore store, NotificationPreferencesRepository preferences,
                                       NotificationDestinations destinations, OrganizationRepository organization, AuthService demo,
                                       FlowableApprovalProxyNotifications proxies, PaymentNotificationAccess payments, SupplierPaymentNotificationAccess supplierPayments, VoucherNotificationAccess vouchers, BudgetNotificationAccess budgets, ReversalNotificationAccess reversals) {
        this.store = store; this.preferences = preferences; this.destinations = destinations; this.organization = organization; this.demo = demo;
        this.proxies = proxies;
        this.payments = payments; this.supplierPayments = supplierPayments;
        this.vouchers = vouchers;
        this.budgets = budgets;
        this.reversals = reversals;
    }

    /** 在开始发送前复核当前人员、原同意、消息归属及固定绑定，过期租约只进入未知。 */
    @Transactional
    public Claim claim(UUID id, Instant now) {
        var identity = store.find(id).orElse(null);
        if (identity == null) return null;
        var currentPreferences = lockRecipient(identity);
        var value = store.lock(id).orElseThrow();
        // 领取可能等待两类行锁；租约起点和抑制事实不能使用等待前的旧时刻。
        Instant afterLocks = Instant.now();
        if (afterLocks.isAfter(now)) now = afterLocks;
        var expired = value.progress().expire(now);
        if (expired != value.progress()) { store.save(value, expired, null, null); return null; }
        if (!value.progress().due(now)) return null;
        var revoked = revoked(value, currentPreferences);
        if (revoked != null) { store.save(value, value.progress().suppress(revoked, now), null, null); return null; }
        var target = destinations.find(value.tenantId(), value.recipient(), value.channel()).orElse(null);
        var unavailable = bindingFailure(value, target);
        if (unavailable != null) { store.save(value, value.progress().failBeforeSend(unavailable, now), null, null); return null; }
        var started = store.save(value, value.progress().start(now, UUID.randomUUID()), null, null);
        return new Claim(started, target);
    }

    /** 只确认原租约的结果；迟到确认不能覆盖过期或人工处理后的状态。 */
    @Transactional
    public boolean finish(NotificationDelivery claimed, Outcome outcome, Instant now) {
        var current = store.lock(claimed.id()).orElse(null);
        if (current == null || !current.progress().matchesClaim(claimed.progress())) return false;
        store.save(current, current.progress().complete(outcome, now), null, null);
        return true;
    }

    /** 人工恢复保持原编号及地址身份；已撤销同意、失去资格或改址的消息不能重试。 */
    @Transactional
    public NotificationDelivery retry(Actor actor, UUID id, long expectedVersion, boolean acknowledgePossibleDuplicate, String reason, Instant now) {
        var identity = store.get(actor, id).orElseThrow(() -> new DomainException("NOTIFICATION_DELIVERY_NOT_FOUND", "Notification delivery not found"));
        var currentPreferences = lockRecipient(identity);
        var value = store.lock(id).orElseThrow();
        var next = value.progress().retry(expectedVersion, acknowledgePossibleDuplicate, now);
        if (revoked(value, currentPreferences) != null) throw new DomainException("NOTIFICATION_CONSENT_REVOKED", "Notification recipient or consent is no longer valid");
        var target = destinations.find(value.tenantId(), value.recipient(), value.channel()).orElse(null);
        if (bindingFailure(value, target) != null) throw new DomainException("NOTIFICATION_BINDING_UNAVAILABLE", "Original notification binding is unavailable");
        return store.save(value, next, actor.userId(), reason);
    }

    /** 本人查询的恢复提示只反映读取时资格，不能代替实际重试的锁定检查。 */
    public NotificationDeliveryViews.RetryEligibility retryEligibility(NotificationDelivery value) {
        boolean duplicateAcknowledgement = value.progress().status() == Status.UNKNOWN;
        if (!value.progress().retryable()) return new NotificationDeliveryViews.RetryEligibility(false, duplicateAcknowledgement, "STATE_NOT_RETRYABLE");
        var failure = revoked(value, preferences.get(value.tenantId(), value.recipient()));
        if (failure == null) failure = bindingFailure(value, destinations.find(value.tenantId(), value.recipient(), value.channel()).orElse(null));
        return new NotificationDeliveryViews.RetryEligibility(failure == null, duplicateAcknowledgement, failure == null ? null : failure.name());
    }

    private NotificationPreferences lockRecipient(NotificationDelivery value) {
        if (organization.initialized(value.tenantId())) organization.lock(value.tenantId());
        return preferences.lock(value.tenantId(), value.recipient());
    }
    private FailureCode revoked(NotificationDelivery value, NotificationPreferences current) {
        if (!current.permits(value.tenantId(), value.recipient(), value.channel(), value.consentGeneration())) return FailureCode.CONSENT_REVOKED;
        boolean active = organization.initialized(value.tenantId())
                ? organization.personBySubject(value.tenantId(), value.recipient()).map(OrganizationPerson::active).orElse(false)
                : demo.activeAccount(value.tenantId(), value.recipient());
        if (!active) return FailureCode.RECIPIENT_INACTIVE;
        // 领取可能等待目录锁，代理期限必须在等待后重新观察；旧消息不随新授权复活。
        return store.ownsMessage(value) && proxies.deliveryAllowed(value, Instant.now()) && payments.deliveryAllowed(value) && supplierPayments.deliveryAllowed(value)
                && vouchers.deliveryAllowed(value) && budgets.deliveryAllowed(value) && reversals.deliveryAllowed(value) ? null : FailureCode.MESSAGE_UNAVAILABLE;
    }
    private static FailureCode bindingFailure(NotificationDelivery value, NotificationDestinations.Destination target) {
        if (value.bindingId() == null || value.destinationDigest() == null) return FailureCode.BINDING_NOT_CAPTURED;
        if (target == null || !target.enabled()) return FailureCode.BINDING_UNAVAILABLE;
        return target.id().equals(value.bindingId()) && target.digest().equals(value.destinationDigest()) ? null : FailureCode.BINDING_CHANGED;
    }

    /** 只有通过当前资格和原目的地检查的领取才携带实际发送目标。
     * @author owlzhangfq@gmail.com
     */
    public record Claim(NotificationDelivery delivery, NotificationDestinations.Destination destination) { }
}
