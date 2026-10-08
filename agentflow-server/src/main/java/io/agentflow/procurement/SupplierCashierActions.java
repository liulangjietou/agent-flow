package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.PaymentAccess;
import io.agentflow.procurement.mapper.SupplierCashierActionsMapper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 出纳的明确选择与原交易恢复，人工说明、幂等回执和最小审计参加同一数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierCashierActions {
    private final CurrentActor actors;
    private final SupplierCashierAccess access;
    private final SupplierPaymentSources sources;
    private final SupplierPaymentExecutionService preparation;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final SupplierPaymentService execution;
    private final SupplierCashierActionsMapper sqlMapper;
    private final JsonUtil json;

    /** 不在人工办理事务中调用财务网关，资金事实由后台按原命令取得。 */
    public SupplierCashierActions(
            CurrentActor actors,
            SupplierCashierAccess access,
            SupplierPaymentSources sources,
            SupplierPaymentExecutionService preparation,
            JdbcSupplierPaymentOperationRepository payments,
            SupplierPaymentService execution,
            SupplierCashierActionsMapper sqlMapper,
            JsonUtil json) {
        this.actors = actors;
        this.access = access;
        this.sources = sources;
        this.preparation = preparation;
        this.payments = payments;
        this.execution = execution;
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 先于幂等缓存回放复核当前角色和法人任职，原号重发还要求原出纳。 */
    public SupplierPaymentAuthorization requireAccess(UUID id, Action action) {
        return switch (action) { case EXECUTE -> access.requireExecution(id); case QUERY -> access.requireCashier(id); case RESEND_ORIGINAL -> access.requireResend(id); };
    }

    /** 执行意图和查询只接受实际展示版本，不允许出纳自填金额、收款账户或新银行编号。 */
    @Transactional
    public Receipt act(UUID id, Input input) {
        var initial = requireAccess(id, input.action());
        var tenant = actors.actor().tenantId();
        sources.lock(tenant, initial.id());
        var authorization = requireAccess(id, input.action());
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID preparationId = null;
        Long preparationVersion = null, operationVersion = null;
        UUID aggregate;
        long version;
        String previous = null, current;
        if (input.action() == Action.EXECUTE) {
            var request =
                    preparation.register(
                            tenant,
                            id,
                            input.holdVersion(),
                            actors.actor().userId(),
                            input.debitAccountReference(),
                            input.debitAccountVersion(),
                            now);
            preparationId = request.input().id();
            preparationVersion = request.version();
            aggregate = preparationId;
            version = request.version();
            current = request.status().name();
        } else {
            var before =
                    payments.find(tenant, id)
                            .orElseThrow(
                                    () ->
                                            new DomainException(
                                                    "NOT_FOUND",
                                                    "Original supplier bank operation is"
                                                            + " unavailable"));
            var next =
                    input.action() == Action.QUERY
                            ? execution.query(tenant, id, input.operationVersion(), now)
                            : execution.resend(tenant, id, input.operationVersion(), now);
            aggregate = id;
            version = next.version();
            operationVersion = version;
            previous = before.status().name();
            current = next.status().name();
        }
        var event = audit(authorization, aggregate, version, input, previous, current, now);
        return new Receipt(
                id, input.action(), preparationId, preparationVersion, operationVersion, event);
    }

    private UUID audit(
            SupplierPaymentAuthorization authorization,
            UUID aggregate,
            long version,
            Input input,
            String previous,
            String current,
            Instant now) {
        var source = authorization.source().reservation().source();
        var event = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", source.requestId());
        payload.put("roundNo", source.round().roundNo());
        payload.put("authorizationId", authorization.id());
        payload.put("authorizedRole", PaymentAccess.CASHIER_ROLE);
        payload.put("previousStatus", previous);
        payload.put("currentStatus", current);
        payload.put("comment", input.comment().trim());
        sqlMapper.audit(
                UUID.randomUUID().toString(),
                source.tenantId(),
                event.toString(),
                aggregate.toString(),
                version,
                source.applicationId().toString(),
                "SUPPLIER_PAYMENT_" + input.action(),
                actors.actor().userId(),
                json.write(payload),
                Timestamp.from(now));
        return event;
    }

    /**
     * 查无重试保留原不可变指令，不能改选账户或生成新的付款身份。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum Action {
        EXECUTE,
        QUERY,
        RESEND_ORIGINAL
    }

    /**
     * 展示版本与账户选择按动作互斥，校验集中在公开入口。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Input(
            @NotNull Action action,
            @Positive Long holdVersion,
            @Positive Long operationVersion,
            @Size(max = 128) String debitAccountReference,
            @Size(max = 128) String debitAccountVersion,
            @NotBlank @Size(max = 2000) String comment) {
        /** 不静默忽略另一操作的字段，未知字段同样拒绝。 */
        public Input {
            if (action != null
                    && (action == Action.EXECUTE
                            ? holdVersion == null
                                    || operationVersion != null
                                    || StringUtils.isBlank(debitAccountReference)
                                    || StringUtils.isBlank(debitAccountVersion)
                            : holdVersion != null
                                    || operationVersion == null
                                    || debitAccountReference != null
                                    || debitAccountVersion != null)) {
                throw new IllegalArgumentException(
                        "Supplier cashier action fields are inconsistent");
            }
        }

        /** 拒绝前端夹带金额、原始账号和银行到账结论。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier cashier action field"); }
    }

    /**
     * 回执只有原操作身份，敏感数据与实时状态通过当前权限重新查询。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(
            UUID authorizationId,
            Action action,
            UUID preparationId,
            Long preparationVersion,
            Long operationVersion,
            UUID auditEventId) {}
}
