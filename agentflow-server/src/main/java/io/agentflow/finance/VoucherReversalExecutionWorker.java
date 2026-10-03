package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 独立冲销后台分两段运行：只读准备和已授权命令执行，所有网络调用均在事务外。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalExecutionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VoucherReversalExecutionWorker.class);
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final VoucherReversalPreparationService preparing;
    private final AccountingVoucherPort originalPort;
    private final AccountingPeriodPort periods;
    private final JdbcVoucherReversalOperationRepository operations;
    private final VoucherReversalExecutionService execution;
    private final AccountingReversalPort port;
    /** 准备不授权，授权不等待网络；两类队列各自保存租约。 */
    public VoucherReversalExecutionWorker(JdbcVoucherReversalPreparationRepository preparations, VoucherReversalPreparationService preparing,
            AccountingVoucherPort originalPort, AccountingPeriodPort periods, JdbcVoucherReversalOperationRepository operations,
            VoucherReversalExecutionService execution, AccountingReversalPort port) {
        this.preparations = preparations; this.preparing = preparing; this.originalPort = originalPort; this.periods = periods;
        this.operations = operations; this.execution = execution; this.port = port;
    }
    /** 每批有界处理，停用或错误没有合成成功兜底。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reversal execution worker must run outside a database transaction");
        prepare(); execute();
    }
    private void prepare() {
        for (var candidate : preparations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = preparing.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); var command = input.source().command(); var original = originalPort.query(input.targetDigest(), command);
                    FinanceResult<AccountingPeriodPort.OpenPeriod> period = new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE);
                    if (original instanceof FinanceResult.Success<VoucherObservation> success && success.value().status() == VoucherObservation.Status.POSTED
                            && input.source().matchesOriginal(success.value())) {
                        period = periods.period(command.tenantId(), input.targetDigest(), new AccountingPeriodPort.Request(command.legalEntityId(), command.totals().gross().currency(), input.accountingDate()));
                    }
                    preparing.finish(claimed, original, period, Instant.now());
                } catch (RuntimeException failure) {
                    preparing.fail(claimed, Instant.now()); LOG.error("Reversal preparation failed, errorCode={}, preparationId={}", "REVERSAL_PREPARATION_FAILURE", candidate.id());
                }
            } catch (RuntimeException failure) { LOG.error("Reversal preparation worker failed, errorCode={}, preparationId={}", "REVERSAL_PREPARATION_WORKER_FAILURE", candidate.id()); }
        }
    }
    private void execute() {
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); var result = claimed.status() == VoucherReversalOperation.Status.POSTING ? port.post(input.targetDigest(), input.command()) : port.query(input.targetDigest(), input.command());
                    execution.finish(claimed, result, Instant.now());
                } catch (RuntimeException failed) {
                    var reason = failed instanceof DomainException domain && "VOUCHER_REVERSAL_EVIDENCE_EXPIRED".equals(domain.code())
                            ? VoucherReversalOperation.Failure.EVIDENCE_EXPIRED : VoucherReversalOperation.Failure.INTERNAL_ERROR;
                    execution.fail(claimed, reason, Instant.now()); LOG.error("Reversal dispatch failed, errorCode={}, reversalId={}", reason, candidate.id());
                }
            } catch (RuntimeException failure) { LOG.error("Reversal execution worker failed, errorCode={}, reversalId={}", "REVERSAL_EXECUTION_WORKER_FAILURE", candidate.id()); }
        }
    }
}
