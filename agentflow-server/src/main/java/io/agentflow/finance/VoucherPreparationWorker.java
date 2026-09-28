package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 事务外查询期间和精确科目，任何失败都不调用过账端口。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherPreparationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VoucherPreparationWorker.class);
    private final JdbcVoucherPreparationRepository preparations;
    private final VoucherPreparationService execution;
    private final AccountingPeriodPort periods;
    private final AccountMappingPort mappings;
    /** 两类只读端口不能直接产生凭证副作用。 */
    public VoucherPreparationWorker(JdbcVoucherPreparationRepository preparations, VoucherPreparationService execution, AccountingPeriodPort periods, AccountMappingPort mappings) {
        this.preparations = preparations; this.execution = execution; this.periods = periods; this.mappings = mappings;
    }
    /** 每批有界，重复领取和迟到结果由持久版本隔离。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Voucher preparation must execute outside a database transaction");
        for (var candidate : preparations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var work = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (work == null) continue;
                var job = work.preparation(); var input = job.input(); var source = work.source();
                try {
                    var period = periods.period(candidate.tenantId(), input.targetDigest(), source.periodRequest());
                    if (!(period instanceof FinanceResult.Success<AccountingPeriodPort.OpenPeriod> availablePeriod)) { execution.finish(job, null, problem(period), Instant.now()); continue; }
                    var mapping = mappings.mapping(candidate.tenantId(), input.targetDigest(), source.mappingRequest());
                    if (!(mapping instanceof FinanceResult.Success<AccountMappingPort.Mapping> availableMapping)) { execution.finish(job, null, problem(mapping), Instant.now()); continue; }
                    var command = source.prepare(input.id(), availablePeriod.value(), availableMapping.value(), Instant.now().truncatedTo(ChronoUnit.MICROS));
                    execution.finish(job, command, null, Instant.now());
                } catch (RuntimeException failure) {
                    var result = failure instanceof DomainException domain ? VoucherPreparationService.classify(domain) : VoucherPreparation.Result.unavailable("INTERNAL_ERROR");
                    execution.finish(job, null, result, Instant.now());
                    LOG.error("Voucher preparation failed, errorCode={}, preparationId={}", result.code(), input.id());
                }
            } catch (RuntimeException failed) { LOG.error("Voucher preparation worker failed, errorCode={}, preparationId={}", "WORKER_FAILURE", candidate.id()); }
        }
    }
    private static VoucherPreparation.Result problem(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Rejected<?> rejected) return VoucherPreparation.Result.blocked(rejected.reason().name());
        if (result instanceof FinanceResult.Unavailable<?> unavailable) return VoucherPreparation.Result.unavailable(unavailable.failure().name());
        return VoucherPreparation.Result.unavailable("INVALID_RESPONSE");
    }
}
