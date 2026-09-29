package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 当前法人出纳工作台，账户选项单独事务外读取，不向出纳授予完整申请读取权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class CashierPaymentWorkspace {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final PaymentAccess access;
    private final ApprovedPaymentSources sources;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentExecutionRequestRepository requests;
    private final JdbcPaymentOperationRepository operations;
    private final PaymentAccountsPort accounts;
    /** 资金目录只接受持久授权的法人、币种、目标以及当前认证出纳。 */
    public CashierPaymentWorkspace(CurrentActor actors, PaymentAccess access, ApprovedPaymentSources sources, JdbcPaymentAuthorizationRepository authorizations,
                                    JdbcPaymentExecutionRequestRepository requests, JdbcPaymentOperationRepository operations, PaymentAccountsPort accounts) {
        this.actors = actors; this.access = access; this.sources = sources; this.authorizations = authorizations;
        this.requests = requests; this.operations = operations; this.accounts = accounts;
    }
    /** 分页边界也必须属于当前法人范围，不能借任意授权号观察其他法人的目录。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Map<String, String> parameters) {
        access.requireCashierRole(); var query = page(parameters); var actor = actors.actor();
        var before = query.beforeId() == null ? null : access.requireCashier(query.beforeId());
        var rows = authorizations.cashierPage(actor.tenantId(), actor.userId(), before == null ? null : before.decision().authorizedAt(), query.beforeId(), query.limit());
        var items = rows.stream().limit(query.limit()).map(this::view).toList();
        return new Page(items, rows.size() > query.limit() ? items.get(items.size() - 1).payment().id() : null);
    }
    /** 状态与操作提示共同读取，不接受客户端传入租户或操作人。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID id) { return view(access.requireCashier(id)); }

    /** 外部等待前后均复核出纳权限和原授权，最终执行仍重新读取账户证据。 */
    @Transactional(propagation = Propagation.NEVER)
    public Accounts accountOptions(UUID id) {
        var authorization = selectable(id); var terms = authorization.terms();
        var query = new PaymentAccountsPort.Request(terms.payee().legalEntityId(), terms.amount().currency(), actors.actor().userId());
        var directory = accounts.debitAccounts(terms.tenantId(), terms.targetDigest(), query).requireValue();
        var current = selectable(id);
        if (!authorization.equals(current)) throw new DomainException("CONCURRENCY_CONFLICT", "Payment authorization changed during account lookup");
        if (!directory.matches(query, Instant.now())) throw new DomainException("PAYMENT_ACCOUNT_EVIDENCE_EXPIRED", "Current cashier account directory is required");
        return new Accounts(id, authorization.version(), directory.validUntil(), directory.accounts());
    }
    private PaymentAuthorization selectable(UUID id) {
        var value = access.requireExecution(id); var now = Instant.now();
        if (value.status() != PaymentAuthorization.Status.AUTHORIZED || requests.forAuthorization(value.terms().tenantId(), id).isPresent()) {
            throw new DomainException("PAYMENT_EXECUTION_ALREADY_REQUESTED", "Authorization is no longer awaiting an account selection");
        }
        if (!now.isBefore(value.decision().expiresAt())) throw new DomainException("PAYMENT_AUTHORIZATION_EXPIRED", "Payment authorization expired");
        sources.requireCurrent(value, now); return value;
    }
    private View view(PaymentAuthorization value) {
        var terms = value.terms(); var actor = actors.actor(); var now = Instant.now();
        var request = requests.forAuthorization(terms.tenantId(), terms.id()).orElse(null);
        var operation = operations.find(terms.tenantId(), terms.id()).orElse(null);
        boolean separated = !actor.userId().equals(terms.payee().employeeId()) && !actor.userId().equals(value.decision().authorizedBy());
        boolean window = now.isBefore(value.decision().expiresAt());
        boolean execute = separated && window && value.status() == PaymentAuthorization.Status.AUTHORIZED && request == null;
        boolean resend = separated && window && value.execution() != null && actor.userId().equals(value.execution().command().authorization().executedBy())
                && operation != null && operation.status() == PaymentOperation.Status.NOT_FOUND && operation.highestRevision() == 0 && operation.conflictingObservation() == null;
        return new View(PaymentView.of(value, request, operation), new Actions(execute, PaymentView.queryable(operation), resend));
    }
    private static Query page(Map<String, String> parameters) {
        if (!Set.of("limit", "beforeId").containsAll(parameters.keySet())) throw invalid();
        try {
            var raw = parameters.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT));
            if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid(); int limit = Integer.parseInt(raw); if (limit > MAX_LIMIT) throw invalid();
            UUID before = parameters.containsKey("beforeId") ? UUID.fromString(parameters.get("beforeId")) : null;
            if (before != null && !before.toString().equals(parameters.get("beforeId"))) throw invalid(); return new Query(limit, before);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_QUERY", "Cashier pagination accepts only a bounded limit and canonical beforeId"); }
    /**
     * 私有分页值不接受外部排序片段。
     * @author owlzhangfq@gmail.com
     */
    private record Query(int limit, UUID beforeId) { }
    /**
     * 目录条目与详情共用最小投影。
     * @author owlzhangfq@gmail.com
     */
    public record View(PaymentView payment, Actions actions) { }
    /**
     * 分页不会扩大读取范围。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<View> items, UUID nextBeforeId) { }
    /**
     * 提示只影响按钮，实际资金动作仍复核来源与人员。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean execute, boolean query, boolean resendOriginal) { }
    /**
     * 账户引用是受控目录选项；不含收款账号、账户摘要或资金系统目标。
     * @author owlzhangfq@gmail.com
     */
    public record Accounts(UUID authorizationId, long authorizationVersion, Instant validUntil, List<PaymentAccountsPort.DebitAccount> items) { }
}
