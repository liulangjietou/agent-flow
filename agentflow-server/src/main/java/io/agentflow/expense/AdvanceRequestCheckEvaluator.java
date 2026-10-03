package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import io.agentflow.finance.FinanceResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import static io.agentflow.expense.AdvanceRequestCheck.*;

/**
 * 事务外查询本人财务目录与账户；借款不调用验票、预算冻结或支付接口。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRequestCheckEvaluator {
    private static final int FACT_TTL_SECONDS = 300;
    private final AdvanceRequestRepository requests;
    private final FinanceMasterDataPort catalogs;
    private final EmployeeAccountPort accounts;
    private final FinanceGatewayConfiguration configuration;
    private final AdvanceOverdueChecks overdue;

    /** 所有财务事实来自真实端口，未配置或查不到时不构造默认账户。 */
    public AdvanceRequestCheckEvaluator(AdvanceRequestRepository requests, FinanceMasterDataPort catalogs, EmployeeAccountPort accounts,
                                        FinanceGatewayConfiguration configuration, AdvanceOverdueChecks overdue) {
        this.requests = requests; this.catalogs = catalogs; this.accounts = accounts; this.configuration = configuration; this.overdue = overdue;
    }

    /** 候选约定使用同一个领域冻结方法，外部成功仍须通过归属与日期检查。 */
    public Result evaluate(AdvanceRequestCheck job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Advance request evaluator must execute outside a database transaction");
        try { return evaluateLive(job); }
        catch (CheckFailure failure) { return failure.result; }
        catch (DomainException rejected) { return Result.blocked(rejected.code()); }
    }

    private Result evaluateLive(AdvanceRequestCheck job) {
        ensureLive(job); var input = job.input();
        var request = requests.find(input.tenantId(), input.requestId()).orElseThrow(() -> new CheckFailure(Result.unavailable("CONTEXT_CHANGED")));
        if (request.version() != input.requestVersion()) return Result.unavailable("CONTEXT_CHANGED");
        var catalog = value(catalogs.catalog(input.tenantId(), input.employeeId()));
        var entity = catalog.legalEntity(input.initiator().legalEntityId());
        ensureLive(job);
        var account = value(accounts.primaryAccount(input.tenantId(), input.employeeId(), entity.id()));
        ensureLive(job);
        Instant checkedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        request.freeze(input.requestVersion(), input.roundNo(), catalog, account, input.initiator(), checkedAt);
        String failure = overdue.failure(input.tenantId(), input.employeeId(), entity, checkedAt);
        if (failure != null) return Result.blocked(failure);
        LocalDate date = LocalDate.ofInstant(checkedAt, ZoneId.of(entity.timeZone()));
        Instant nextDay = date.plusDays(1).atStartOfDay(ZoneId.of(entity.timeZone())).toInstant();
        Instant validUntil = Stream.of(checkedAt.plusSeconds(FACT_TTL_SECONDS), catalog.validUntil(), account.validUntil(), nextDay)
                .min(Instant::compareTo).orElseThrow();
        if (!validUntil.isAfter(Instant.now())) return Result.unavailable("FACTS_EXPIRED");
        return Result.ready(new Evidence(catalog, account, request.currentRound(), validUntil));
    }

    private void ensureLive(AdvanceRequestCheck job) {
        if (Thread.currentThread().isInterrupted() || job.expired(Instant.now())) throw new CheckFailure(Result.unavailable("TIMEOUT"));
        var destination = configuration.destination(job.input().tenantId()).orElse(null);
        if (destination == null) throw new CheckFailure(Result.unavailable("NOT_CONFIGURED"));
        if (!destination.digest(job.input().tenantId()).equals(job.input().targetDigest())) throw new CheckFailure(Result.unavailable("TARGET_CHANGED"));
    }

    private static <T> T value(FinanceResult<T> result) {
        if (result instanceof FinanceResult.Success<T> success) return success.value();
        if (result instanceof FinanceResult.Rejected<T> rejected) throw new CheckFailure(Result.blocked(rejected.reason().name()));
        throw new CheckFailure(Result.unavailable(((FinanceResult.Unavailable<T>) result).failure().name()));
    }

    /**
     * 内部中止只携带封闭分类，不输出外部正文。
     * @author owlzhangfq@gmail.com
     */
    private static final class CheckFailure extends RuntimeException {
        private final Result result;
        private CheckFailure(Result result) { super(result.code()); this.result = result; }
    }
}
