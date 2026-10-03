package io.agentflow.servicetask;

import io.agentflow.approval.process.ServiceTaskWaitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 领取、完成和引擎推进各自使用短事务；等待状态不把已发出的外部副作用误作已取消。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskOperationService {
    private static final Duration HOLD_DELAY = Duration.ofSeconds(5);
    private final JdbcServiceTaskOperationRepository operations;
    private final ServiceTaskCatalog catalog;
    private final ServiceTaskWaitService waits;
    private final Duration lease;

    /** 领取上限与领域一致，留出远端请求及结果提交的余量。 */
    public ServiceTaskOperationService(JdbcServiceTaskOperationRepository operations, ServiceTaskCatalog catalog,
            ServiceTaskWaitService waits, @Value("${agentflow.service-tasks.lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Service task lease must be between 15 and 300 seconds");
        this.operations = operations; this.catalog = catalog; this.waits = waits; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 查询领取不依赖原流程仍在运行；首次发送及引擎推进必须属于原活动等待。 */
    @Transactional
    public ServiceTaskOperation claim(String tenant, UUID id, Instant time) {
        var initial = operations.find(tenant, id).orElse(null); if (initial == null) return null;
        var context = waits.lock(initial.operation().input().command());
        var stored = operations.lock(tenant, id).orElseThrow(); var current = stored.operation(); Instant now = time(time);
        if (stored.progress() != JdbcServiceTaskOperationRepository.Progress.PENDING) return null;
        if (current.status() == ServiceTaskOperation.Status.APPLIED) {
            if (context.state() == ServiceTaskWaitService.State.STALE) operations.markProgress(stored, JdbcServiceTaskOperationRepository.Progress.STALE, now);
            else if (context.state() == ServiceTaskWaitService.State.PAUSED || !available(current)) operations.postpone(stored, now.plus(HOLD_DELAY));
            else { waits.advance(context, current); operations.markProgress(stored, JdbcServiceTaskOperationRepository.Progress.ADVANCED, now); }
            return null;
        }
        if (current.terminal()) return null;
        if (current.expired(now)) { operations.update(current.expire(now)); return null; }
        if (current.running() || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == ServiceTaskOperation.Status.QUEUED) {
            if (context.state() == ServiceTaskWaitService.State.STALE) {
                var cancelled = current.cancelUnsent(now); operations.update(cancelled);
                operations.markProgress(new JdbcServiceTaskOperationRepository.Stored(cancelled, stored.progress(), null), JdbcServiceTaskOperationRepository.Progress.STALE, now);
                return null;
            }
            if (context.state() == ServiceTaskWaitService.State.PAUSED || !available(current)) { operations.postpone(stored, now.plus(HOLD_DELAY)); return null; }
        }
        var claimed = current.claim(now, lease); operations.update(claimed); return claimed;
    }

    /** 迟到执行者只被忽略；真实结果的落库不能与原领取版本或原命令分离。 */
    @Transactional
    public void finish(ServiceTaskOperation claimed, ServiceTaskGateway.Result result, Instant time) {
        var command = claimed.input().command(); waits.lock(command);
        var stored = operations.lock(command.tenantId(), command.id()).orElseThrow(); var current = stored.operation();
        if (stored.progress() != JdbcServiceTaskOperationRepository.Progress.PENDING || current.version() != claimed.version()
                || current.status() != claimed.status() || !current.running() || !current.input().equals(claimed.input())) return;
        Instant now = time(time);
        var completed = result instanceof ServiceTaskGateway.Observed observed ? current.complete(observed.value(), now)
                : current.unavailable(result instanceof ServiceTaskGateway.Unavailable unavailable ? unavailable.failure() : ServiceTaskOperation.Failure.INVALID_RESPONSE, now);
        operations.update(completed);
    }

    /** 引擎或数据库短暂失败后的调度退避不修改命令、结果或审批事实。 */
    @Transactional
    public void retryLater(String tenant, UUID id, Instant now) {
        var stored = operations.lock(tenant, id).orElse(null); if (stored == null || stored.progress() != JdbcServiceTaskOperationRepository.Progress.PENDING) return;
        var operation = stored.operation(); Instant until = time(now).plus(HOLD_DELAY);
        Instant earliest = operation.running() ? operation.leaseUntil() : operation.nextAttemptAt();
        if (earliest != null && earliest.isAfter(until)) until = earliest;
        operations.postpone(stored, until);
    }

    private boolean available(ServiceTaskOperation operation) {
        var input = operation.input();
        return catalog.available(input.command().tenantId(), new ServiceTaskCatalog.Installed(input.command().contract(), input.targetDigest()));
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
}
