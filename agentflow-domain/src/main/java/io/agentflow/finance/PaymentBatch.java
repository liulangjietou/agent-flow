package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 一次明确出纳选择形成的不可变付款集合；每笔仍使用原授权和独立执行请求。
 * @author owlzhangfq@gmail.com
 */
public record PaymentBatch(UUID id, String tenantId, UUID legalEntityId, String currency, String targetDigest,
                           String cashier, String debitReference, String debitVersion, String comment,
                           Instant createdAt, List<Item> items) {
    public static final int MAX_ITEMS = 25;

    /** 批次不保存可被误读为到账的汇总状态，也不允许修改或重复加入原授权。 */
    public PaymentBatch {
        if (id == null || invalidText(tenantId) || tenantId.length() > 64 || legalEntityId == null
                || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}") || invalidText(cashier)
                || invalidText(debitReference) || invalidText(debitVersion) || StringUtils.isBlank(comment) || comment.length() > 2000
                || createdAt == null || CollectionUtils.isEmpty(items) || items.size() > MAX_ITEMS) throw invalid();
        var authorizations = new HashSet<UUID>(); var requests = new HashSet<UUID>();
        for (var item : items) {
            if (item == null || !item.amount().currency().equals(currency) || !authorizations.add(item.authorizationId()) || !requests.add(item.requestId())) throw invalid();
        }
        comment = comment.trim(); items = List.copyOf(items);
    }

    /** 只将同目标、同法人和同币种的实际初始执行登记归为一个批次，不能凭选择清单生成付款事实。 */
    public static PaymentBatch submitted(UUID id, String comment, List<Registration> registrations, Instant now) {
        if (CollectionUtils.isEmpty(registrations) || registrations.size() > MAX_ITEMS || registrations.stream().anyMatch(Objects::isNull) || now == null) throw invalid();
        var first = registrations.get(0); var terms = first.authorization().terms(); var choice = first.request().input();
        for (var registration : registrations) {
            var authorization = registration.authorization(); var request = registration.request(); var other = authorization.terms();
            if (!terms.tenantId().equals(other.tenantId()) || !terms.payee().legalEntityId().equals(other.payee().legalEntityId())
                    || !terms.amount().currency().equals(other.amount().currency()) || !terms.targetDigest().equals(other.targetDigest())
                    || now.isBefore(request.createdAt())
                    || !request.equals(PaymentExecutionRequest.queue(request.input().id(), authorization, choice.cashier(), choice.debitReference(), choice.debitVersion(), request.createdAt()))) {
                throw invalid();
            }
        }
        return new PaymentBatch(id, terms.tenantId(), terms.payee().legalEntityId(), terms.amount().currency(), terms.targetDigest(),
                choice.cashier(), choice.debitReference(), choice.debitVersion(), comment, now,
                registrations.stream().map(value -> new Item(value.authorization().terms().id(), value.authorization().version(), value.request().input().id(), value.authorization().terms().amount())).toList());
    }

    /** 汇总只为核对选择；多笔合计可超过单笔金额上限，仍使用十进制精确相加。 */
    public String total() { return items.stream().map(value -> value.amount().value()).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(Money.SCALE).toPlainString(); }

    /** 日志不展开账户选择、金额或人工说明。 */
    @Override public String toString() { return "PaymentBatch[id=" + id + ", items=" + items.size() + "]"; }
    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_BATCH", "Payment batch requires bounded distinct original requests in one payment scope"); }

    /**
     * 记录已确认的授权修订、唯一执行请求和当时金额，不创建第二个资金编号。
     * @author owlzhangfq@gmail.com
     */
    public record Item(UUID authorizationId, long authorizationVersion, UUID requestId, Money amount) {
        /** 初次执行只能登记第一版授权，批次不会接管重发或结果未知的付款。 */
        public Item {
            if (authorizationId == null || authorizationVersion != 1 || requestId == null || amount == null || amount.value().signum() <= 0) throw invalid();
        }
    }

    /**
     * 只用于创建时核对已经持久登记的两份原始事实，不作为批次持久内容。
     * @author owlzhangfq@gmail.com
     */
    public record Registration(PaymentAuthorization authorization, PaymentExecutionRequest request) {
        /** 实际来源必须同时提供，不能使用只有编号的客户端声明代替。 */
        public Registration { if (authorization == null || request == null) throw invalid(); }
    }
}
