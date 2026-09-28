package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 按授权轮次显示准备与实际过账，禁止把任何队列完成当作付款成功。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherWorkspace {
    private final VoucherAccess access;
    private final JdbcVoucherPreparationRepository preparations;
    private final JdbcVoucherOperationRepository operations;
    /** 查询仅投影状态、凭证号和时间，不返回外部目标、账户、科目或原始响应。 */
    public VoucherWorkspace(VoucherAccess access, JdbcVoucherPreparationRepository preparations, JdbcVoucherOperationRepository operations) {
        this.access = access; this.preparations = preparations; this.operations = operations;
    }
    /** 一个数据库快照内读取当轮事实和操作提示；写入仍会重新检查权限和状态。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID applicationId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) {
            try { if (!parameters.get("roundNo").matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.parseInt(parameters.get("roundNo")); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        var context = access.read(applicationId, round); var application = context.application();
        var preparation = preparations.latest(application.tenantId(), applicationId, context.roundNo(), context.kind()).orElse(null);
        var operation = operations.forRound(application.tenantId(), applicationId, context.roundNo(), context.kind()).orElse(null);
        boolean current = application.status() == ApplicationStatus.APPROVED && application.roundNo() == context.roundNo();
        boolean finance = context.finance();
        boolean prepare = finance && current && operation == null && (preparation == null || !preparation.active() && preparation.status() != VoucherPreparation.Status.NOT_REQUIRED);
        boolean query = finance && operation != null && !operation.running() && operation.status() != VoucherOperation.Status.QUEUED;
        boolean resend = finance && current && operation != null && operation.status() == VoucherOperation.Status.NOT_FOUND && operation.highestRevision() == 0
                && operation.conflictingObservation() == null && operation.input().command().expiresAt().isAfter(Instant.now())
                && operation.input().command().binding().applicationVersion() == application.version() && operation.input().command().binding().businessVersion() == context.businessVersion();
        return new View(applicationId, application.businessReference().type(), application.businessReference().id(), context.roundNo(), application.version(), context.businessVersion(),
                preparation == null ? null : new Preparation(preparation.input().id(), preparation.status(), preparation.input().attempt(), preparation.createdAt(), preparation.completedAt(), preparation.result() == null ? null : preparation.result().code()),
                operation == null ? null : operation(operation), new Actions(prepare, query, resend));
    }
    private static Operation operation(VoucherOperation operation) {
        var command = operation.input().command(); var observation = operation.observation();
        String issue = operation.failure() != null ? operation.failure().name() : observation != null && observation.failure() != null ? observation.failure().name() : null;
        return new Operation(command.id(), operation.version(), command.kind(), operation.status(), operation.attempts(), command.accountingDate(),
                operation.updatedAt(), command.expiresAt(), observation == null ? null : observation.status(), observation == null ? null : observation.voucherReference(),
                observation == null ? null : observation.postedAt(), operation.conflictingObservation() != null, issue);
    }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_QUERY", "Only a positive roundNo is accepted for voucher status"); }
    /**
     * 所有状态均限定在明确的业务与轮次，客户端须核对绑定后展示。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, BusinessReference.Type businessType, UUID businessId, int roundNo, long applicationVersion, long businessVersion,
                       Preparation preparation, Operation operation, Actions actions) { }
    /**
     * 不公开准备的财务目标或完整输入。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, VoucherPreparation.Status status, long attempt, Instant createdAt, Instant completedAt, String issue) { }
    /**
     * 冲突时保留旧凭证信息并明确不可用状态，不展示未经确认的新凭证号。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(UUID id, long version, VoucherCommand.Kind kind, VoucherOperation.Status status, int attempts, LocalDate accountingDate,
                            Instant updatedAt, Instant sendExpiresAt, VoucherObservation.Status observedStatus, String voucherReference, Instant postedAt, boolean disputed, String issue) { }
    /**
     * 操作提示不作为写入授权，服务端每次重新判断。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean prepare, boolean query, boolean resendOriginal) { }
}
