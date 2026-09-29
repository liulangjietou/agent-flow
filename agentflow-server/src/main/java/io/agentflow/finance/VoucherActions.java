package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 财务操作只调度实际副作用，不接受金额、账户、科目、ERP 结果或外部目的地。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherActions {
    private final CurrentActor actors;
    private final VoucherAccess access;
    private final PaymentAccess payments;
    private final ApprovedVoucherSources sources;
    private final VoucherPreparationService preparations;
    private final JdbcVoucherOperationRepository operations;
    private final VoucherOperationService execution;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 权限、原事实、版本与审计共用一个事务，后台再执行网络查询或原编号重发。 */
    public VoucherActions(CurrentActor actors, VoucherAccess access, PaymentAccess payments, ApprovedVoucherSources sources, VoucherPreparationService preparations,
                          JdbcVoucherOperationRepository operations, VoucherOperationService execution, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.sources = sources; this.preparations = preparations;
        this.payments = payments;
        this.operations = operations; this.execution = execution; this.jdbc = jdbc; this.json = json;
    }
    /** 锁后重新检查当轮访问和批准版本，历史查询不触发另一轮命令。 */
    @Transactional
    public Receipt act(UUID applicationId, Input input) {
        return apply(applicationId, input, false);
    }
    /** 付款会计操作复核当前法人任职；审批随后变化不妨碍处理已发生付款的原凭证。 */
    @Transactional
    public Receipt payment(UUID applicationId, Input input) { return apply(applicationId, input, true); }
    private Receipt apply(UUID applicationId, Input input, boolean payment) {
        var initial = payment ? payments.requireFinance(applicationId, input.roundNo()) : access.requireFinance(applicationId, input.roundNo()); sources.lock(initial.source());
        var context = payment ? payments.requireFinance(applicationId, input.roundNo()) : access.requireFinance(applicationId, input.roundNo());
        var application = context.application(); var actor = actors.actor(); var kind = payment ? VoucherCommand.Kind.PAYMENT : context.kind();
        if (application.version() != input.applicationVersion() || context.businessVersion() != input.businessVersion()) throw conflict();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!payment && input.action() != Action.QUERY && (application.status() != ApplicationStatus.APPROVED || application.roundNo() != input.roundNo())) {
            throw new DomainException("VOUCHER_SOURCE_CHANGED", "Original financial approval is no longer current");
        }
        UUID preparationId = null, operationId = null; Long operationVersion = null; long revision; String previous = null, current;
        if (input.action() == Action.PREPARE) {
            var preparation = payment ? preparations.retryPayment(actor.tenantId(), applicationId, input.roundNo(), actor.userId(), now)
                    : preparations.retry(actor.tenantId(), applicationId, input.applicationVersion(), actor.userId(), now);
            preparationId = preparation.input().id(); revision = preparation.version(); current = preparation.status().name();
        } else {
            var original = operations.find(actor.tenantId(), input.operationId()).orElseThrow(VoucherActions::notFound); var command = original.input().command();
            if (!command.binding().applicationId().equals(applicationId) || command.binding().roundNo() != input.roundNo() || command.kind() != kind) throw notFound();
            if (original.version() != input.operationVersion()) throw conflict(); previous = original.status().name();
            var operation = input.action() == Action.QUERY ? execution.query(actor.tenantId(), command.id(), input.operationVersion(), now)
                    : execution.resend(actor.tenantId(), command.id(), input.operationVersion(), now);
            operationId = operation.input().command().id(); operationVersion = operation.version(); revision = operation.version(); current = operation.status().name();
        }
        UUID eventId = UUID.randomUUID(); var payload = new LinkedHashMap<String, Object>();
        payload.put("roundNo", input.roundNo()); payload.put("action", input.action().name()); payload.put("actor", actor.userId()); payload.put("authorizedRole", "FINANCE");
        payload.put("applicationVersion", application.version()); payload.put("businessVersion", context.businessVersion()); payload.put("previousStatus", previous);
        payload.put("currentStatus", current); payload.put("comment", input.comment().trim());
        payload.put("kind", kind.name());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'Voucher',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), actor.tenantId(), eventId.toString(), (operationId == null ? preparationId : operationId).toString(), revision,
                applicationId.toString(), (payment ? "PAYMENT_VOUCHER_" : "VOUCHER_") + input.action().name(), actor.userId(), json.write(payload), java.sql.Timestamp.from(now));
        return new Receipt(applicationId, application.businessReference().id(), input.roundNo(), input.action(), preparationId, operationId, operationVersion, eventId, kind);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed approval, financial or voucher version has changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Voucher for the selected financial round not found"); }
    /**
     * 重新准备、原操作查询与权威查无后的原编号重发分别授权和审计。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { PREPARE, QUERY, RESEND_ORIGINAL }
    /**
     * 入口仅接收已展示版本和操作原因，不允许客户端构造会计事实。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull Action action, @Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion,
                        UUID operationId, @Positive Long operationVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 每种动作只允许自身需要的标识，避免用额外字段混淆原操作。 */
        public Input {
            if (action != null && (action == Action.PREPARE ? operationId != null || operationVersion != null : operationId == null || operationVersion == null)) {
                throw new DomainException("INVALID_REQUEST", "Voucher action must identify exactly its original operation");
            }
        }
        /** 在入口拒绝契约外字段，不能把客户端金额或 ERP 声明静默丢弃后执行操作。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown voucher action field"); }
    }
    /**
     * 幂等回执不携带财务敏感内容，读最新状态须重新经过权限投影。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(UUID applicationId, UUID businessId, int roundNo, Action action, UUID preparationId, UUID operationId,
                          Long operationVersion, UUID auditEventId, VoucherCommand.Kind kind) { }
}
