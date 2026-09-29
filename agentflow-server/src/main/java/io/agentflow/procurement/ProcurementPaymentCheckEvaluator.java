package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import io.agentflow.finance.FinanceResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static io.agentflow.procurement.ProcurementPaymentCheck.*;

/**
 * 事务外读取本人目录与原供应商应付；预览复用正式冻结规则，不产生财务写入。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProcurementPaymentCheckEvaluator {
    private final ProcurementPaymentRepository requests;
    private final FinanceMasterDataPort catalogs;
    private final ProcurementPayablePort payables;
    private final FinanceGatewayConfiguration configuration;

    /** 目录和原应付均来自财务端口，不能由客户端提供账户或匹配通过状态。 */
    public ProcurementPaymentCheckEvaluator(ProcurementPaymentRepository requests, FinanceMasterDataPort catalogs,
                                           ProcurementPayablePort payables, FinanceGatewayConfiguration configuration) {
        this.requests = requests; this.catalogs = catalogs; this.payables = payables; this.configuration = configuration;
    }

    /** 外部不可用和已确认的业务阻断分别保存，网络等待不持有申请锁。 */
    public Result evaluate(ProcurementPaymentCheck job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Procurement evaluator must execute outside a database transaction");
        try { return evaluateLive(job); }
        catch (CheckFailure failure) { return failure.result; }
        catch (DomainException rejected) { return Result.blocked(rejected.code()); }
    }

    private Result evaluateLive(ProcurementPaymentCheck job) {
        ensureLive(job); var input = job.input();
        var request = requests.find(input.tenantId(), input.requestId()).orElseThrow(() -> new CheckFailure(Result.unavailable("CONTEXT_CHANGED")));
        if (request.version() != input.requestVersion() || !request.content().equals(input.content())) return Result.unavailable("CONTEXT_CHANGED");
        var catalog = value(catalogs.catalog(input.tenantId(), input.employeeId()));
        catalog.legalEntity(input.initiator().legalEntityId()); ensureLive(job);
        var payable = value(payables.payable(input.tenantId(), input.targetDigest(), input.content().payableRequest(input.employeeId())));
        ensureLive(job); Instant checkedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        request.freeze(input.requestVersion(), input.roundNo(), catalog, input.targetDigest(), payable, input.initiator(), checkedAt);
        Instant validUntil = Stream.of(catalog.validUntil(), payable.validUntil(), payable.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE))
                .min(Instant::compareTo).orElseThrow();
        if (!validUntil.isAfter(Instant.now())) return Result.unavailable("FACTS_EXPIRED");
        return Result.ready(new Evidence(catalog, request.currentRound(), validUntil));
    }

    private void ensureLive(ProcurementPaymentCheck job) {
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
     * 内部中止仅携带受控分类，不保留远端错误正文。
     * @author owlzhangfq@gmail.com
     */
    private static final class CheckFailure extends RuntimeException {
        private final Result result;
        private CheckFailure(Result result) { super(result.code()); this.result = result; }
    }
}
