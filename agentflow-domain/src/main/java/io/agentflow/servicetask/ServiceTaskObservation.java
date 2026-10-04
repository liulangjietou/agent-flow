package io.agentflow.servicetask;

import io.agentflow.common.DomainException;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 服务返回的有界事实；终态必须带原命令摘要与结果凭据，不接收流程变量或任意错误正文。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskObservation(UUID operationId, String commandDigest, Status status, String reference, Instant completedAt) {
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    /** 待定和未找到没有终态凭据，已执行和明确拒绝必须具备可查询的凭据。 */
    public ServiceTaskObservation {
        if (operationId == null || commandDigest == null || !ServiceTaskCommand.DIGEST.matcher(commandDigest).matches() || status == null) throw invalid();
        boolean terminal = status == Status.APPLIED || status == Status.REJECTED;
        if (terminal ? reference == null || !REFERENCE.matcher(reference).matches() || completedAt == null
                : reference != null || completedAt != null) throw invalid();
    }

    /** 查无原操作只能由原号查询确认，发送返回的空结果不能授权重发。 */
    public boolean matches(ServiceTaskCommand command, boolean queried, Instant now) {
        return operationId.equals(command.id()) && commandDigest.equals(command.digest())
                && (status != Status.NOT_FOUND || queried) && (completedAt == null || !completedAt.isAfter(now));
    }

    /**
     * APPLIED 仅表示声明的服务操作已执行，不代表审批通过、付款成功或预算已冻结。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { APPLIED, REJECTED, PENDING, NOT_FOUND }

    private static DomainException invalid() { return new DomainException("INVALID_SERVICE_TASK_OBSERVATION", "Service task observation must identify the original command and a bounded, consistent result"); }
}
