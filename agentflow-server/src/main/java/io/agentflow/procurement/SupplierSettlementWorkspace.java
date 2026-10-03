package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentObservation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原银行、ERP 核销和本地完成分别投影，固定指令、内部账户与财务目标不出现在页面数据中。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementWorkspace {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final SupplierSettlementAccess access;
    private final SupplierSettlementSources sources;
    private final JdbcSupplierSettlementPreparationRepository preparations;
    private final JdbcSupplierPayableSettlementRepository settlements;
    private final JdbcProcurementPayableReservationRepository reservations;

    /** 当前状态和历史使用同一快照，实际写操作仍重新检查权限及来源。 */
    public SupplierSettlementWorkspace(CurrentActor actors, SupplierSettlementAccess access, SupplierSettlementSources sources,
            JdbcSupplierSettlementPreparationRepository preparations, JdbcSupplierPayableSettlementRepository settlements, JdbcProcurementPayableReservationRepository reservations) {
        this.actors = actors; this.access = access; this.sources = sources; this.preparations = preparations; this.settlements = settlements; this.reservations = reservations;
    }

    /** 历史按原银行有界分页，不能把其他付款游标用于探测其结算。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID paymentId, Map<String, String> parameters) {
        var query = pagination(parameters); var context = access.read(paymentId); var authorization = context.authorization();
        var original = authorization.source().reservation(); var source = original.source(); var actor = actors.actor(); var tenant = actor.tenantId(); var now = Instant.now();
        var before = query.beforeId() == null ? null : settlements.find(tenant, query.beforeId()).orElseThrow(SupplierSettlementWorkspace::invalid);
        if (before != null && !before.command().payment().id().equals(paymentId)) throw invalid();
        var rows = settlements.page(tenant, paymentId, before, query.limit());
        var items = rows.stream().limit(query.limit()).map(value -> operation(value, context.finance(), now)).toList();
        var active = settlements.active(tenant, paymentId).orElse(null);
        var pending = preparations.active(tenant, paymentId).orElseGet(() -> preparations.latest(tenant, paymentId).orElse(null));
        var reservation = reservations.find(tenant, original.id()).orElseThrow(() -> new DomainException("NOT_FOUND", "Original payable reservation is unavailable"));
        var bank = context.payment(); var observation = bank == null ? null : bank.observation();
        var minimumDate = observation != null && observation.status() == PaymentObservation.Status.SUCCEEDED
                ? observation.completedAt().atZone(ZoneId.of(source.round().legalEntity().timeZone())).toLocalDate() : null;
        var completion = reservation.settlement();
        return new View(paymentId, source.requestId(), source.applicationId(), source.round().roundNo(), source.round().content().legalEntityId(), authorization.payable().supplierName(),
                authorization.source().amount(), authorization.payable().account().maskedAccount(), minimumDate,
                bank == null ? null : new Bank(bank.version(), bank.status(), bank.command().cashier(), bank.updatedAt(), observation == null ? null : observation.status(),
                        observation == null ? null : observation.paymentReference(), observation == null ? null : observation.receiptReference(), observation == null ? null : observation.completedAt(), bank.conflictingObservation() != null),
                pending == null ? null : new Preparation(pending.input().id(), pending.version(), pending.status(), pending.input().financeActor(), pending.input().accountingDate(), pending.updatedAt(), pending.issue()),
                active == null ? null : active.command().id(), items, rows.size() > query.limit() ? items.get(items.size() - 1).id() : null,
                completion == null ? null : new Completion(completion.operationId(), completion.operationVersion(), completion.completedAt()),
                context.finance() && bank != null && active == null && (pending == null || !pending.active()) && eligible(bank.command(), actor.userId(), now));
    }
    private Operation operation(SupplierPayableSettlementOperation value, boolean finance, Instant now) {
        var command = value.command(); var retired = settlements.retirement(command.tenantId(), command.id()).orElse(null); var observation = value.observation();
        var posting = observation == null ? null : observation.posting(); boolean actionable = finance && retired == null;
        return new Operation(command.id(), value.version(), value.status(), command.financeActor(), command.period().request().accountingDate(), command.period().periodReference(), value.createdAt(), value.updatedAt(),
                value.attempts(), value.dispatches(), observation == null ? null : observation.status(), value.conflictingObservation() != null,
                value.failure() == null ? null : value.failure().name(), observation == null ? null : observation.rejection(),
                posting == null ? null : new Posting(posting.settlementReference(), posting.voucherReference(), posting.settledAmount(), posting.settledAt()),
                retired == null ? null : new Retirement(retired.retiredBy(), retired.retiredAt(), retired.basis()),
                new Actions(actionable && !value.running() && value.status() != SupplierPayableSettlementOperation.Status.QUEUED && value.dispatches() > 0,
                        actionable && value.status() == SupplierPayableSettlementOperation.Status.NOT_FOUND && value.highestRevision() == 0 && value.conflictingObservation() == null && eligible(command.payment(), command.financeActor(), now),
                        actionable && value.retirementBasis() != null));
    }
    private boolean eligible(SupplierPaymentCommand payment, String finance, Instant now) {
        try { sources.requireCurrent(payment, finance, now); return true; } catch (DomainException changed) { return false; }
    }
    private static Query pagination(Map<String, String> parameters) {
        if (!Set.of("limit", "beforeId").containsAll(parameters.keySet())) throw invalid();
        try {
            var raw = parameters.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT)); if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
            int limit = Integer.parseInt(raw); if (limit > MAX_LIMIT) throw invalid();
            UUID before = parameters.containsKey("beforeId") ? UUID.fromString(parameters.get("beforeId")) : null;
            if (before != null && !before.toString().equals(parameters.get("beforeId"))) throw invalid(); return new Query(limit, before);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_SETTLEMENT_QUERY", "Settlement history requires a bounded limit and a canonical cursor from the same original payment"); }

    /**
     * 排序固定，不接收客户端 SQL 字段。
     * @author owlzhangfq@gmail.com
     */
    private record Query(int limit, UUID beforeId) { }
    /**
     * 银行成功、ERP 核销及本地完成独立展示，空完成信息不能被解释成已归档。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID paymentId, UUID requestId, UUID applicationId, int roundNo, UUID legalEntityId, String supplierName, Money amount, String maskedPayeeAccount,
            LocalDate minimumAccountingDate, Bank bank, Preparation preparation, UUID activeSettlementId, List<Operation> items, UUID nextBeforeId, Completion completion, boolean canPrepare) { }
    /**
     * 银行只回显原状态、回单与掩码之外的必要业务身份。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Bank(long version, SupplierPaymentOperation.Status status, String cashier, Instant updatedAt, PaymentObservation.Status observedStatus,
            String paymentReference, String receiptReference, Instant completedAt, boolean disputed) { }
    /**
     * READY 只表示原结算已登记，不能替代 ERP 结果。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, long version, SupplierSettlementPreparation.Status status, String financeActor, LocalDate accountingDate, Instant updatedAt, SupplierSettlementPreparation.Issue issue) { }
    /**
     * 历史只暴露原决定、外部状态和必要凭证，完整请求始终受控。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(UUID id, long version, SupplierPayableSettlementOperation.Status status, String financeActor, LocalDate accountingDate, String periodReference,
            Instant createdAt, Instant updatedAt, int attempts, int dispatches, SupplierPayableSettlementObservation.Status observedStatus, boolean disputed, String issue,
            SupplierPayableSettlementObservation.Rejection rejection, Posting posting, Retirement retirement, Actions actions) { }
    /**
     * 只返回已匹配原回单和原应付的核销凭据。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(String settlementReference, String voucherReference, Money amount, Instant settledAt) { }
    /**
     * 安全结束作为独立历史事实保留。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String retiredBy, Instant retiredAt, SupplierPayableSettlementOperation.RetirementBasis basis) { }
    /**
     * 本地完成指向实际保存的 ERP 终态修订。
     * @author owlzhangfq@gmail.com
     */
    public record Completion(UUID settlementId, long settlementVersion, Instant completedAt) { }
    /**
     * 操作能力仅供展示，入口仍会核对当前资格及版本。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean query, boolean retryOriginal, boolean retire) { }
}
