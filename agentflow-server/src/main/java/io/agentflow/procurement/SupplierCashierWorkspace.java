package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.finance.PaymentPersonnel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原供应商授权的出纳目录与最小付款视图，外部账户查询不跨数据库事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierCashierWorkspace {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final SupplierCashierAccess access;
    private final PaymentPersonnel personnel;
    private final SupplierPaymentSources sources;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPayableHoldRepository holds;
    private final JdbcSupplierPaymentExecutionRepository requests;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final PaymentAccountsPort accounts;

    /** 所有展示来源都使用原授权，不从出纳提交内容组装金融事实。 */
    public SupplierCashierWorkspace(CurrentActor actors, SupplierCashierAccess access, PaymentPersonnel personnel, SupplierPaymentSources sources,
            JdbcSupplierPaymentAuthorizationRepository authorizations, JdbcSupplierPayableHoldRepository holds,
            JdbcSupplierPaymentExecutionRepository requests, JdbcSupplierPaymentOperationRepository payments, PaymentAccountsPort accounts) {
        this.actors = actors; this.access = access; this.personnel = personnel; this.sources = sources; this.authorizations = authorizations;
        this.holds = holds; this.requests = requests; this.payments = payments; this.accounts = accounts;
    }
    /** 当前任职在 SQL 分页前限定候选集，越权游标不能观察其他法人的授权。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Map<String, String> parameters) {
        access.requireRole(); var query = pagination(parameters); var actor = actors.actor();
        var before = query.beforeId() == null ? null : access.requireCashier(query.beforeId());
        var rows = authorizations.cashierPage(actor.tenantId(), personnel.legalEntities(actor.tenantId(), actor.userId()), before == null ? null : before.authorizedAt(), query.beforeId(), query.limit());
        var items = rows.stream().limit(query.limit()).map(this::view).toList();
        return new Page(items, rows.size() > query.limit() ? items.get(items.size() - 1).authorizationId() : null);
    }
    /** 该投影不会公开完整采购表单、票号、账户引用、摘要或财务目标。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID id) { return view(access.requireCashier(id)); }

    /** 外部等待前后重新核验实际出纳及原来源，目录有效期不超过观察时间加五分钟。 */
    @Transactional(propagation = Propagation.NEVER)
    public Accounts accountOptions(UUID id) {
        var selected = selectable(id); var authorization = selected.command().authorization(); var actor = actors.actor();
        var query = new PaymentAccountsPort.Request(authorization.payable().request().legalEntityId(), authorization.source().amount().currency(), actor.userId());
        var directory = accounts.debitAccounts(actor.tenantId(), selected.command().targetDigest(), query).requireValue(); var current = selectable(id);
        if (!selected.equals(current)) throw conflict(); var now = Instant.now();
        var freshness = directory.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE);
        if (!directory.matches(query, now) || !freshness.isAfter(now)) throw new DomainException("PAYMENT_ACCOUNT_EVIDENCE_EXPIRED", "Fresh cashier account directory is required");
        return new Accounts(id, current.version(), directory.validUntil().isBefore(freshness) ? directory.validUntil() : freshness, directory.accounts());
    }
    private SupplierPayableHoldOperation selectable(UUID id) {
        var authorization = access.requireExecution(id); var actor = actors.actor();
        sources.requireCurrent(authorization, actor.userId(), Instant.now()); var hold = sources.held(authorization);
        if (payments.find(actor.tenantId(), id).isPresent() || requests.owner(actor.tenantId(), id).isPresent()) {
            throw new DomainException("SUPPLIER_PAYMENT_EXECUTION_PENDING", "Supplier payment already has a saved cashier choice or bank command");
        }
        return hold;
    }
    private View view(SupplierPaymentAuthorization authorization) {
        var source = authorization.source().reservation().source(); var tenant = source.tenantId(); var user = actors.actor().userId(); var now = Instant.now();
        var hold = holds.find(tenant, authorization.id()).orElseThrow(SupplierCashierWorkspace::conflict);
        var request = requests.owner(tenant, authorization.id()).orElseGet(() -> requests.latest(tenant, authorization.id()).orElse(null));
        var payment = payments.find(tenant, authorization.id()).orElse(null);
        var retirement = authorizations.retirement(tenant, authorization.id()).orElse(null);
        boolean separated = !user.equals(source.employeeId()) && !user.equals(authorization.authorizedBy());
        boolean window = now.isBefore(authorization.expiresAt());
        boolean execute = separated && window && retirement == null && hold.status() == SupplierPayableHoldOperation.Status.HELD && payment == null && (request == null || !request.ownsAuthorization());
        boolean query = payment != null && !payment.running() && payment.status() != SupplierPaymentOperation.Status.QUEUED && payment.dispatches() > 0;
        boolean resend = separated && window && retirement == null && hold.status() == SupplierPayableHoldOperation.Status.HELD && payment != null && payment.command().cashier().equals(user)
                && payment.status() == SupplierPaymentOperation.Status.NOT_FOUND && payment.highestRevision() == 0 && payment.conflictingObservation() == null;
        return View.of(authorization, hold, request, payment, retirement == null ? null : retirement.retiredAt(), new Actions(execute, query, resend));
    }
    private static Operation operation(SupplierPaymentOperation value) {
        var observation = value.observation();
        return new Operation(value.version(), value.status(), value.command().cashier(), value.updatedAt(), observation == null ? null : observation.status(),
                observation == null ? null : observation.paymentReference(), observation == null ? null : observation.receiptReference(), observation == null ? null : observation.completedAt(), value.conflictingObservation() != null,
                value.failure() != null ? value.failure().name() : observation != null && observation.failure() != null ? observation.failure().name() : null);
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
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_QUERY", "Supplier cashier pagination accepts only a bounded limit and canonical beforeId"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier authorization or confirmed hold changed"); }

    /**
     * 排序字段固定，查询参数不能注入 SQL。
     * @author owlzhangfq@gmail.com
     */
    private record Query(int limit, UUID beforeId) { }
    /**
     * 最小资金字段不包含审批敏感正文或原始银行命令。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID authorizationId, UUID requestId, UUID applicationId, int roundNo, UUID legalEntityId, String employeeId, String supplierName,
            Money amount, String maskedPayeeAccount, String authorizedBy, Instant authorizedAt, Instant expiresAt, Instant retiredAt,
            Hold hold, Preparation preparation, Operation operation, Actions actions) {
        /** 调用方已完成权限与原来源定位，只投影必要资金字段；历史消息明确传入原登记请求。 */
        public static View of(SupplierPaymentAuthorization authorization, SupplierPayableHoldOperation hold,
                              SupplierPaymentExecutionRequest request, SupplierPaymentOperation payment, Instant retiredAt, Actions actions) {
            var source = authorization.source().reservation().source();
            return new View(authorization.id(), source.requestId(), source.applicationId(), source.round().roundNo(), source.round().content().legalEntityId(),
                    source.employeeId(), authorization.payable().supplierName(), authorization.source().amount(), authorization.payable().account().maskedAccount(),
                    authorization.authorizedBy(), authorization.authorizedAt(), authorization.expiresAt(), retiredAt,
                    new Hold(hold.version(), hold.status(), hold.updatedAt()), request == null ? null : new Preparation(request.input().id(), request.version(), request.status(), request.input().cashier(), request.updatedAt(), request.failure() == null ? null : request.failure().name()),
                    payment == null ? null : SupplierCashierWorkspace.operation(payment), actions);
        }
    }
    /**
     * 分页游标仍受当前法人范围限制。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<View> items, UUID nextBeforeId) { }
    /**
     * 预留仅显示状态，原号、账本和摘要保持受控。
     * @author owlzhangfq@gmail.com
     */
    public record Hold(long version, SupplierPayableHoldOperation.Status status, Instant updatedAt) { }
    /**
     * 已选择账户不在查询视图回显，READY 只表示银行命令已登记。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, long version, SupplierPaymentExecutionRequest.Status status, String cashier, Instant updatedAt, String issue) { }
    /**
     * 已接受银行事实与争议标记分开显示，不把待查当作失败。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(long version, SupplierPaymentOperation.Status status, String cashier, Instant updatedAt, PaymentObservation.Status observedStatus,
            String paymentReference, String receiptReference, Instant completedAt, boolean disputed, String issue) { }
    /**
     * 按钮提示不代替提交时的真实来源与权限核验。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean execute, boolean query, boolean resendOriginal) { }
    /**
     * 仅账户选择入口返回出款目录引用，不能由此替换供应商收款账户。
     * @author owlzhangfq@gmail.com
     */
    public record Accounts(UUID authorizationId, long holdVersion, Instant validUntil, List<PaymentAccountsPort.DebitAccount> items) { }
}
