package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
    private final PaymentPersonnel personnel;
    /** 资金目录只接受持久授权的法人、币种、目标以及当前认证出纳。 */
    public CashierPaymentWorkspace(CurrentActor actors, PaymentAccess access, ApprovedPaymentSources sources, JdbcPaymentAuthorizationRepository authorizations,
                                    JdbcPaymentExecutionRequestRepository requests, JdbcPaymentOperationRepository operations, PaymentAccountsPort accounts, PaymentPersonnel personnel) {
        this.actors = actors; this.access = access; this.sources = sources; this.authorizations = authorizations;
        this.requests = requests; this.operations = operations; this.accounts = accounts; this.personnel = personnel;
    }
    /** 分页边界也必须属于当前法人范围，不能借任意授权号观察其他法人的目录。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Map<String, String> parameters) {
        access.requireCashierRole(); var query = page(parameters); var actor = actors.actor();
        var before = query.beforeId() == null ? null : access.requireCashier(query.beforeId());
        if (before != null && !authorizations.cashierMatches(actor.tenantId(), actor.userId(), query.filter(), query.beforeId())) throw invalid();
        long count = authorizations.cashierCount(actor.tenantId(), actor.userId(), query.filter());
        var rows = authorizations.cashierPage(actor.tenantId(), actor.userId(), query.filter(), before, query.limit());
        var items = rows.stream().limit(query.limit()).map(this::view).toList();
        return new Page(items, rows.size() > query.limit() ? items.get(items.size() - 1).payment().id() : null, count);
    }

    /** 历史实际出款账户按同一法人权限分页，未固定账户不猜测当前目录或默认账户。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FilterOptions filterOptions(Map<String, String> parameters) {
        access.requireCashierRole();
        if (!Set.of("limit", "legalEntityId", "afterAccountKey").containsAll(parameters.keySet())) throw invalid();
        int limit = limit(parameters); UUID legal = identifier(parameters, "legalEntityId"); String after = parameters.get("afterAccountKey");
        if (after != null && !accountKey(after)) throw invalid();
        var actor = actors.actor();
        if (after != null && authorizations.cashierCount(actor.tenantId(), actor.userId(), new JdbcPaymentAuthorizationRepository.CashierFilter(legal, after)) == 0) throw invalid();
        var rows = authorizations.cashierAccounts(actor.tenantId(), actor.userId(), legal, after, limit);
        var options = rows.stream().limit(limit).map(CashierPaymentWorkspace::accountOption).toList();
        return new FilterOptions(personnel.legalEntityOptions(actor.tenantId(), actor.userId()), options,
                rows.size() > limit ? options.get(options.size() - 1).key() : null);
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
        boolean resend = separated && window && value.status() == PaymentAuthorization.Status.EXECUTION_REGISTERED && actor.userId().equals(value.execution().command().authorization().executedBy())
                && operation != null && operation.status() == PaymentOperation.Status.NOT_FOUND && operation.highestRevision() == 0 && operation.conflictingObservation() == null;
        return new View(PaymentView.of(value, request, operation), new Actions(execute, PaymentView.queryable(value, operation), resend), accountOption(value));
    }
    private static AccountOption accountOption(PaymentAuthorization value) {
        if (value.execution() == null) return null;
        var debit = value.execution().debitAccount();
        return new AccountOption(CashierPaymentAccountKey.of(value), value.terms().payee().legalEntityId(), debit.currency(), debit.displayName(), debit.maskedAccount());
    }
    private static Query page(Map<String, String> parameters) {
        if (!Set.of("limit", "beforeId", "legalEntityId", "debitAccount", "dueFrom", "dueTo", "undated", "sort").containsAll(parameters.keySet())) throw invalid();
        String account = parameters.get("debitAccount");
        if (account != null && !JdbcPaymentAuthorizationRepository.CashierFilter.UNASSIGNED.equals(account) && !accountKey(account)) throw invalid();
        var from = date(parameters, "dueFrom"); var to = date(parameters, "dueTo");
        boolean undated = parameters.containsKey("undated");
        if (undated && (!"true".equals(parameters.get("undated")) || from != null || to != null) || from != null && to != null && from.isAfter(to)) throw invalid();
        JdbcPaymentAuthorizationRepository.CashierSort sort;
        try { sort = JdbcPaymentAuthorizationRepository.CashierSort.valueOf(parameters.getOrDefault("sort", "AUTHORIZED_AT_DESC")); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        return new Query(limit(parameters), identifier(parameters, "beforeId"),
                new JdbcPaymentAuthorizationRepository.CashierFilter(identifier(parameters, "legalEntityId"), account, from, to, undated, sort));
    }
    private static LocalDate date(Map<String, String> parameters, String key) {
        if (!parameters.containsKey(key)) return null;
        String value = parameters.get(key);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try {
            var date = LocalDate.parse(value); if (date.isBefore(PaymentAuthorization.MIN_DUE_DATE)) throw invalid(); return date;
        } catch (DateTimeParseException failure) { throw invalid(); }
    }
    private static int limit(Map<String, String> parameters) {
        try {
            var raw = parameters.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT));
            if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid(); int limit = Integer.parseInt(raw); if (limit > MAX_LIMIT) throw invalid();
            return limit;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static UUID identifier(Map<String, String> parameters, String key) {
        if (!parameters.containsKey(key)) return null;
        try {
            UUID value = UUID.fromString(parameters.get(key)); if (!value.toString().equals(parameters.get(key))) throw invalid(); return value;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static boolean accountKey(String value) { return value.matches("[a-f0-9]{64}"); }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_QUERY", "Cashier filters and pagination must use bounded limits and canonical identifiers in the same scope"); }
    /**
     * 私有分页值不接受外部排序片段。
     * @author owlzhangfq@gmail.com
     */
    private record Query(int limit, UUID beforeId, JdbcPaymentAuthorizationRepository.CashierFilter filter) { }
    /**
     * 目录条目与详情共用最小投影。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(PaymentView payment, Actions actions, AccountOption debitAccount) { }
    /**
     * 分页不会扩大读取范围。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<View> items, UUID nextBeforeId, long totalCount) { }
    /**
     * 账户键固定资金目标、法人、币种和账户引用，只公开历史脱敏展示。
     * @author owlzhangfq@gmail.com
     */
    public record AccountOption(String key, UUID legalEntityId, String currency, String displayName, String maskedAccount) { }
    /**
     * 账户选项独立分页，不因截断选项而宣称目录完整。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FilterOptions(List<PaymentPersonnel.LegalEntity> legalEntities, List<AccountOption> accounts, String nextAfterAccountKey) { }
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
