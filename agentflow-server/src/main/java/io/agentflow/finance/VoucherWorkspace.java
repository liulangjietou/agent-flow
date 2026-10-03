package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.CurrentActor;
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
    private final VoucherSources sources;
    private final PaymentPersonnel personnel;
    private final CurrentActor actors;
    private final VoucherDisputeService disputes;
    /** 查询仅投影状态、凭证号、时间和科目版本身份，不返回外部目标、账户、科目或原始响应。 */
    public VoucherWorkspace(VoucherAccess access, JdbcVoucherPreparationRepository preparations, JdbcVoucherOperationRepository operations,
                            VoucherSources sources, PaymentPersonnel personnel, CurrentActor actors, VoucherDisputeService disputes) {
        this.access = access; this.preparations = preparations; this.operations = operations;
        this.sources = sources; this.personnel = personnel; this.actors = actors;
        this.disputes = disputes;
    }
    /** 一个数据库快照内读取当轮事实和操作提示；写入仍会重新检查权限和状态。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID applicationId, Map<String, String> parameters) {
        return read(applicationId, parameters, false);
    }
    /** 付款凭证按相同字段权限单独读取，不将挂账准备或凭证号混入本视图。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View payment(UUID applicationId, Map<String, String> parameters) {
        return read(applicationId, parameters, true);
    }
    private View read(UUID applicationId, Map<String, String> parameters, boolean payment) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) {
            try { if (!parameters.get("roundNo").matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.parseInt(parameters.get("roundNo")); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        var context = access.read(applicationId, round); var application = context.application();
        var kind = payment ? VoucherCommand.Kind.PAYMENT : context.kind();
        var preparation = preparations.latest(application.tenantId(), applicationId, context.roundNo(), kind).orElse(null);
        var operation = operations.forRound(application.tenantId(), applicationId, context.roundNo(), kind).orElse(null);
        boolean current = application.status() == ApplicationStatus.APPROVED && application.roundNo() == context.roundNo();
        boolean finance = context.finance();
        if (payment) {
            var accrual = operations.forRound(application.tenantId(), applicationId, context.roundNo(), context.kind()).orElse(null);
            finance = finance && accrual != null && personnel.eligible(application.tenantId(), actors.actor().userId(), accrual.input().command().legalEntityId());
        }
        boolean prepare = finance && (payment ? preparation != null : current) && operation == null
                && (preparation == null || !preparation.active() && preparation.status() != VoucherPreparation.Status.NOT_REQUIRED);
        boolean query = finance && operation != null && !operation.running() && operation.status() != VoucherOperation.Status.QUEUED;
        boolean resend = finance && (payment || current) && operation != null && operation.status() == VoucherOperation.Status.NOT_FOUND && operation.highestRevision() == 0
                && operation.conflictingObservation() == null && operation.input().command().expiresAt().isAfter(Instant.now())
                && (payment ? paymentSourceMatches(operation) : operation.input().command().binding().applicationVersion() == application.version()
                    && operation.input().command().binding().businessVersion() == context.businessVersion());
        return new View(applicationId, application.businessReference().type(), application.businessReference().id(), context.roundNo(), application.version(), context.businessVersion(),
                preparation == null ? null : new Preparation(preparation.input().id(), preparation.status(), preparation.input().attempt(), preparation.createdAt(), preparation.completedAt(), preparation.result() == null ? null : preparation.result().code()),
                operation == null ? null : operation(operation), new Actions(prepare, query, resend), kind, disputes.view(context, operation, Instant.now()), mappingEvidence(preparation, operation));
    }
    private boolean paymentSourceMatches(VoucherOperation operation) {
        try { return sources.derive(sources.reference(operation.input().command())).matches(operation.input().command()); }
        catch (DomainException changed) { return false; }
    }
    private static Operation operation(VoucherOperation operation) {
        var command = operation.input().command(); var observation = operation.observation();
        String issue = operation.failure() != null ? operation.failure().name() : observation != null && observation.failure() != null ? observation.failure().name() : null;
        return new Operation(command.id(), operation.version(), command.kind(), operation.status(), operation.attempts(), command.accountingDate(),
                operation.updatedAt(), command.expiresAt(), observation == null ? null : observation.status(), observation == null ? null : observation.voucherReference(),
                observation == null ? null : observation.postedAt(), operation.conflictingObservation() != null, issue);
    }
    /** 已登记命令优先于准备记录；只读原始绑定，不能用当前发布配置推断历史。 */
    private static MappingEvidence mappingEvidence(VoucherPreparation preparation, VoucherOperation operation) {
        var mapping = operation == null ? null : operation.input().command().mapping();
        var request = mapping != null ? mapping.request() : preparation == null ? null : preparation.mappingRequest();
        if (request == null) return null;
        var selected = request.managedMapping() == null ? null : request.managedMapping().selection();
        return new MappingEvidence(selected == null ? MappingSource.ERP_MANAGED : MappingSource.PLATFORM_PUBLISHED,
                request.legalEntityId(), request.currency(), selected == null ? null : selected.mappingId(),
                selected == null ? null : selected.mappingVersion(), selected == null ? null : selected.categoryRevision(),
                selected == null ? null : selected.activeRevision(), selected == null ? null : selected.definitionDigest(),
                mapping == null ? null : mapping.sourceVersion());
    }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_QUERY", "Only a positive roundNo is accepted for voucher status"); }
    /**
     * 所有状态均限定在明确的业务与轮次，客户端须核对绑定后展示。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, BusinessReference.Type businessType, UUID businessId, int roundNo, long applicationVersion, long businessVersion,
                       Preparation preparation, Operation operation, Actions actions, VoucherCommand.Kind kind, VoucherDisputeService.View dispute, MappingEvidence mapping) { }
    /**
     * 平台选择与 ERP 管理分别声明，不将准备阶段的意图说成 ERP 已确认事实。
     * @author owlzhangfq@gmail.com
     */
    public enum MappingSource { ERP_MANAGED, PLATFORM_PUBLISHED }
    /**
     * 经过业务字段读取授权的原版本证据；ERP 来源版本仅在原凭证命令登记后提供。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MappingEvidence(MappingSource source, UUID legalEntityId, String currency, UUID mappingId,
                                  Long mappingVersion, Long categoryRevision, Long activeRevision,
                                  String definitionDigest, String erpSourceVersion) { }
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
