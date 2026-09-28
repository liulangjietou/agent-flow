package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.ExchangeRatePort;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import io.agentflow.finance.FinanceResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import static io.agentflow.expense.ExpensePlanCheck.*;

/**
 * 外部财务读取在数据库事务之外完成；目录、币种和成本对象由计划领域统一校验。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePlanCheckEvaluator {
    private static final int FACT_TTL_SECONDS = 300;
    private final ExpensePlanRepository plans;
    private final FinanceMasterDataPort catalogs;
    private final ExchangeRatePort rates;
    private final FinanceGatewayConfiguration configuration;

    /** 事前计划不调用报销账户、验票或预算冻结接口。 */
    public ExpensePlanCheckEvaluator(ExpensePlanRepository plans, FinanceMasterDataPort catalogs, ExchangeRatePort rates, FinanceGatewayConfiguration configuration) {
        this.plans = plans; this.catalogs = catalogs; this.rates = rates; this.configuration = configuration;
    }

    /** 任一依赖失败都不生成部分可用的预检。 */
    public Result evaluate(ExpensePlanCheck job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense plan evaluator must execute outside a database transaction");
        try { return evaluateLive(job); }
        catch (CheckFailure failure) { return failure.result; }
        catch (DomainException rejected) { return Result.blocked(rejected.code()); }
    }

    private Result evaluateLive(ExpensePlanCheck job) {
        ensureLive(job); var input = job.input();
        var plan = plans.find(input.tenantId(), input.planId()).orElseThrow(() -> new CheckFailure(Result.unavailable("CONTEXT_CHANGED")));
        if (plan.version() != input.planVersion()) return Result.unavailable("CONTEXT_CHANGED");
        var catalog = value(catalogs.catalog(input.tenantId(), input.employeeId()));
        var entity = catalog.legalEntity(input.initiator().legalEntityId());
        Instant checkedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        LocalDate date = LocalDate.ofInstant(checkedAt, ZoneId.of(entity.timeZone()));
        var conversions = new HashMap<String, ExpenseExchangeRate>();
        for (String currency : plan.content().lines().stream().map(line -> line.amount().currency()).distinct().sorted().toList()) {
            ensureLive(job);
            conversions.put(currency, value(rates.rate(input.tenantId(), entity.id(), currency, entity.baseCurrency(), date)));
        }
        ensureLive(job);
        plan.freeze(input.planVersion(), input.roundNo(), catalog, conversions, input.initiator(), checkedAt);
        Instant validUntil = checkedAt.plusSeconds(FACT_TTL_SECONDS);
        if (validUntil.isAfter(catalog.validUntil())) validUntil = catalog.validUntil();
        Instant nextRateDay = date.plusDays(1).atStartOfDay(ZoneId.of(entity.timeZone())).toInstant();
        if (validUntil.isAfter(nextRateDay)) validUntil = nextRateDay;
        if (!validUntil.isAfter(Instant.now())) return Result.unavailable("FACTS_EXPIRED");
        return Result.ready(new Evidence(catalog, conversions, plan.currentRound(), validUntil));
    }

    private void ensureLive(ExpensePlanCheck job) {
        if (Thread.currentThread().isInterrupted() || job.expired(Instant.now())) throw new CheckFailure(Result.unavailable("TIMEOUT"));
        var destination = configuration.destination(job.input().tenantId()).orElse(null);
        if (destination == null) throw new CheckFailure(Result.unavailable("NOT_CONFIGURED"));
        if (!destination.digest(job.input().tenantId()).equals(job.input().targetDigest())) throw new CheckFailure(Result.unavailable("TARGET_CHANGED"));
    }

    private static <T> T value(FinanceResult<T> result) {
        if (result instanceof FinanceResult.Success<T> success) return success.value();
        if (result instanceof FinanceResult.Rejected<T> rejection) throw new CheckFailure(Result.blocked(rejection.reason().name()));
        throw new CheckFailure(Result.unavailable(((FinanceResult.Unavailable<T>) result).failure().name()));
    }

    /**
     * 内部中止仅传递封闭分类，不包含远端错误文本。
     * @author owlzhangfq@gmail.com
     */
    private static final class CheckFailure extends RuntimeException {
        private final Result result;
        private CheckFailure(Result result) { super(result.code()); this.result = result; }
    }
}
