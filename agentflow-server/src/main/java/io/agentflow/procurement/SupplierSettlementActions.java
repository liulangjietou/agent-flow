package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.procurement.mapper.SupplierSettlementActionsMapper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 财务结算决定与最小审计同事务登记，公开输入只包含原版本、明确日期及人工说明。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementActions {
    private final CurrentActor actors;
    private final SupplierSettlementAccess access;
    private final SupplierSettlementSources sources;
    private final SupplierSettlementPreparationService preparation;
    private final SupplierSettlementService execution;
    private final SupplierSettlementActionsMapper sqlMapper;
    private final JsonUtil json;

    /** 固定金额、回单和三单资料由服务端取得，入口不等待外部账务系统。 */
    public SupplierSettlementActions(
            CurrentActor actors,
            SupplierSettlementAccess access,
            SupplierSettlementSources sources,
            SupplierSettlementPreparationService preparation,
            SupplierSettlementService execution,
            SupplierSettlementActionsMapper sqlMapper,
            JsonUtil json) {
        this.actors = actors;
        this.access = access;
        this.sources = sources;
        this.preparation = preparation;
        this.execution = execution;
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 锁后复查当前权限，保存财务明确确认的原付款及记账日期。 */
    @Transactional
    public Receipt prepare(UUID paymentId, PrepareInput input) {
        access.requireFinance(paymentId); var actor = actors.actor(); sources.lock(actor.tenantId(), paymentId);
        var context = access.requireFinance(paymentId); var now = now();
        var value = preparation.register(actor.tenantId(), paymentId, input.paymentVersion(), actor.userId(), input.accountingDate(), now);
        var event = audit(context.authorization(), value.input().id(), value.version(), "SUPPLIER_SETTLEMENT_PREPARE", null, value.status().name(), input.comment(), now);
        var source = context.authorization().source().reservation().source();
        return new Receipt(paymentId, source.requestId(), source.applicationId(), source.round().roundNo(), "PREPARE", value.input().id(), value.version(), null, null, event);
    }

    /** 查询、原号重试和安全结束分别执行，幂等回执不能替换当前版本判断。 */
    @Transactional
    public Receipt act(UUID settlementId, ActionInput input) {
        var before = access.requireSettlement(settlementId); var actor = actors.actor(); sources.lock(actor.tenantId(), before.command().payment().id());
        before = access.requireSettlement(settlementId); var now = now(); long version; String state;
        if (input.action() == Action.RETIRE) {
            var retired = execution.retire(actor.tenantId(), settlementId, input.settlementVersion(), actor.userId(), now); version = retired.operationVersion(); state = "RETIRED";
        } else {
            var next = input.action() == Action.QUERY ? execution.query(actor.tenantId(), settlementId, input.settlementVersion(), now)
                    : execution.resend(actor.tenantId(), settlementId, input.settlementVersion(), now);
            version = next.version(); state = next.status().name();
        }
        var authorization = before.command().payment().holdCommand().authorization();
        var event = audit(authorization, settlementId, version, "SUPPLIER_SETTLEMENT_" + input.action().name(), before.status().name(), state, input.comment(), now);
        var source = authorization.source().reservation().source();
        return new Receipt(authorization.id(), source.requestId(), source.applicationId(), source.round().roundNo(), input.action().name(), null, null, settlementId, version, event);
    }

    private UUID audit(
            SupplierPaymentAuthorization authorization,
            UUID aggregateId,
            long version,
            String action,
            String before,
            String after,
            String comment,
            Instant now) {
        var source = authorization.source().reservation().source();
        var event = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", source.requestId());
        payload.put("roundNo", source.round().roundNo());
        payload.put("paymentId", authorization.id());
        payload.put("authorizedRole", "FINANCE");
        payload.put("previousStatus", before);
        payload.put("currentStatus", after);
        payload.put("comment", comment.trim());
        sqlMapper.audit(
                UUID.randomUUID().toString(),
                source.tenantId(),
                event.toString(),
                aggregateId.toString(),
                version,
                source.applicationId().toString(),
                action,
                actors.actor().userId(),
                json.write(payload),
                Timestamp.from(now));
        return event;
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 恢复动作只作用于原结算号，不接受替换指令。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum Action {
        QUERY,
        RETRY,
        RETIRE
    }

    /**
     * 只有展示过的银行版本、明确日期和具名说明能进入准备意图。
     *
     * @author owlzhangfq@gmail.com
     */
    public record PrepareInput(
            @Positive long paymentVersion,
            @NotNull LocalDate accountingDate,
            @NotBlank @Size(max = 2000) String comment) {
        /** 拒绝客户端伪造金额、回单、会计期间或财务身份。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier settlement preparation field"); }
    }

    /**
     * 版本绑定页面看到的原结算状态，未知结果不能借请求换号。
     *
     * @author owlzhangfq@gmail.com
     */
    public record ActionInput(
            @NotNull Action action,
            @Positive long settlementVersion,
            @NotBlank @Size(max = 2000) String comment) {
        /** 不静默忽略新日期、金额或成功声明。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier settlement action field"); }
    }

    /**
     * 缓存回执仅定位决定及审计，不缓存原资金状态或完整单据。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(
            UUID paymentId,
            UUID requestId,
            UUID applicationId,
            int roundNo,
            String action,
            UUID preparationId,
            Long preparationVersion,
            UUID settlementId,
            Long settlementVersion,
            UUID auditEventId) {}
}
