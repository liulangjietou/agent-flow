package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次复用原出纳用例，逐笔意图、审计及批次关联原子登记，资金执行仍独立恢复。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentBatchService {
    private final CurrentActor actors;
    private final PaymentAccess access;
    private final PaymentPersonnel personnel;
    private final ApprovedPaymentSources sources;
    private final CashierPaymentActions actions;
    private final JdbcPaymentExecutionRequestRepository requests;
    private final JdbcPaymentBatchRepository batches;
    private final CashierPaymentWorkspace workspace;

    /** 应用服务负责跨原授权的锁顺序与事务，批次自身仅承担不可变成员约束。 */
    public PaymentBatchService(CurrentActor actors, PaymentAccess access, PaymentPersonnel personnel, ApprovedPaymentSources sources,
            CashierPaymentActions actions, JdbcPaymentExecutionRequestRepository requests, JdbcPaymentBatchRepository batches, CashierPaymentWorkspace workspace) {
        this.actors = actors; this.access = access; this.personnel = personnel; this.sources = sources;
        this.actions = actions; this.requests = requests; this.batches = batches; this.workspace = workspace;
    }

    /** 即使只回放原幂等回执，也核验当前出纳、法人任职和每笔职责分离。 */
    public void requireAccess(Input input) { access.requireCashierRole(); input.items().forEach(item -> access.requireExecution(item.authorizationId())); }

    /** 按原申请编号锁定，两个重叠批次不能因页面勾选顺序不同而形成相反锁序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt submit(Input input) {
        requireAccess(input); var tenant = actors.actor().tenantId();
        var initial = input.items().stream().map(item -> access.requireExecution(item.authorizationId()))
                .sorted(Comparator.comparing((PaymentAuthorization value) -> value.terms().binding().applicationId().toString()).thenComparing(value -> value.terms().id().toString())).toList();
        initial.forEach(sources::lock);
        var registrations = new HashMap<UUID, PaymentBatch.Registration>();
        var selections = new HashMap<UUID, Selection>(); input.items().forEach(item -> selections.put(item.authorizationId(), item));
        for (var previous : initial) {
            var id = previous.terms().id(); var current = access.requireExecution(id); var selected = selections.get(id);
            var receipt = actions.act(id, new CashierPaymentActions.Input(CashierPaymentActions.Action.EXECUTE, selected.authorizationVersion(), null,
                    input.debitAccountReference(), input.debitAccountVersion(), input.comment()));
            var request = requests.find(tenant, receipt.requestId()).orElseThrow(); registrations.put(id, new PaymentBatch.Registration(current, request));
        }
        var batch = PaymentBatch.submitted(UUID.randomUUID(), input.comment(), input.items().stream().map(item -> registrations.get(item.authorizationId())).toList(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        batches.create(batch); return new Receipt(batch.id(), batch.createdAt(), batch.items().size());
    }

    /** 批次仅返回当前法人内的最小付款视图，每笔保留其独立账户复查和银行状态。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail get(UUID id) {
        var batch = accessible(id); var items = new ArrayList<Item>();
        for (var item : batch.items()) {
            var current = workspace.get(item.authorizationId());
            if (current.payment().request() == null || !current.payment().request().id().equals(item.requestId())) throw new IllegalStateException("Payment batch original request is unavailable");
            items.add(new Item(item.authorizationVersion(), item.requestId(), current));
        }
        return new Detail(Summary.of(batch), batch.comment(), List.copyOf(items));
    }

    /** 上页锚点也需当前读取权限；分页不按缓存中的旧任职扩大范围。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(int limit, UUID beforeId) {
        access.requireCashierRole(); var actor = actors.actor(); var before = beforeId == null ? null : accessible(beforeId);
        var values = batches.page(actor.tenantId(), actor.userId(), before, limit); var items = values.stream().limit(limit).map(Summary::of).toList();
        return new Page(items, values.size() > limit ? items.get(items.size() - 1).id() : null);
    }

    private PaymentBatch accessible(UUID id) {
        access.requireCashierRole(); var actor = actors.actor(); var value = batches.find(actor.tenantId(), id).orElseThrow(PaymentBatchService::notFound);
        if (!personnel.eligible(actor.tenantId(), actor.userId(), value.legalEntityId())) throw notFound(); return value;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Payment batch is unavailable in the current cashier scope"); }

    /**
     * 客户端只能选择实际展示的原授权修订，不能传入收款人或金额。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(@NotNull UUID authorizationId, @Positive long authorizationVersion) {
        /** 未知资金字段在入口直接拒绝。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown payment batch selection field"); }
    }
    /**
     * 一个批次明确选择同一付款账户与版本；各笔仍在后台重查当前账户目录。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotEmpty @Size(max = PaymentBatch.MAX_ITEMS) List<@NotNull @Valid Selection> items,
                        @NotBlank @Size(max = 128) String debitAccountReference, @NotBlank @Size(max = 128) String debitAccountVersion,
                        @NotBlank @Size(max = 2000) String comment) {
        /** 重复授权不能被列表去重后悄悄执行；选择清单保存原显示顺序。 */
        public Input {
            if (items != null) {
                items = List.copyOf(items); var ids = new HashSet<UUID>();
                if (items.stream().anyMatch(item -> !ids.add(item.authorizationId()))) throw new IllegalArgumentException("Duplicate payment batch authorization");
            }
        }
        /** 金额、目标、租户、角色及回执都从真实原授权取得。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown payment batch request field"); }
    }
    /**
     * 批次回执仅代表意图已登记，不能用于确认资金已到账。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID batchId, Instant createdAt, int itemCount) { }
    /**
     * 汇总金额按十进制字符串展示，无批次付款成功状态。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, UUID legalEntityId, String currency, String total, int itemCount, String cashier, Instant createdAt) {
        static Summary of(PaymentBatch value) { return new Summary(value.id(), value.legalEntityId(), value.currency(), value.total(), value.items().size(), value.cashier(), value.createdAt()); }
    }
    /**
     * 每笔关联原请求和当前付款投影，查询及查无重发仍由原单笔入口承担。
     * @author owlzhangfq@gmail.com
     */
    public record Item(long authorizationVersion, UUID requestId, CashierPaymentWorkspace.View current) { }
    /**
     * 历史批次说明保持原文，展示实时单笔状态时重新核验读取权限。
     * @author owlzhangfq@gmail.com
     */
    public record Detail(Summary batch, String comment, List<Item> items) { }
    /**
     * 有界目录总是返回明确的末页标志。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<Summary> items, UUID nextBeforeId) { }
}
