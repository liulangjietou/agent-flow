package io.agentflow.agent;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 自动办理的明确授权、暂停恢复及撤销入口，所有写入保留原幂等键。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/handling-tasks/{taskId}/agent")
public class ExpenseAgentController {
    private final ExpenseAgentService service;
    private final ExpenseHandlingService handling;
    private final IdempotencyExecutor idempotency;
    /** 原业务归属先于幂等回放检查。 */
    public ExpenseAgentController(ExpenseAgentService service, ExpenseHandlingService handling, IdempotencyExecutor idempotency) {
        this.service = service; this.handling = handling; this.idempotency = idempotency;
    }
    /** 读取原授权和步骤；尚未授权返回空值。 */
    @GetMapping public ResponseEntity<ExpenseAgentService.View> get(@PathVariable UUID id, @PathVariable UUID taskId, HttpServletRequest request) {
        noQuery(request); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, taskId));
    }
    /** 来源预览不产生模型调用或业务写入。 */
    @PostMapping("/preview") public ResponseEntity<ExpenseAgentService.Preview> preview(@PathVariable UUID id, @PathVariable UUID taskId,
            @Valid @RequestBody Scope scope, HttpServletRequest request) {
        noQuery(request); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(id, taskId, scope.domain()));
    }
    /** 202 表示授权已登记，后台每一步继续复核原登录和单据双版本。 */
    @PostMapping public ResponseEntity<String> start(@PathVariable UUID id, @PathVariable UUID taskId, @Valid @RequestBody Start body, HttpServletRequest request) {
        noQuery(request); handling.authorizeTask(id, taskId);
        return idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.start(id, taskId, body.scope().domain(), body.consentDigest(), body.targetDigest(), request));
    }
    /** 回答只作为业务材料，不能扩大工具范围或替换原目标。 */
    @PostMapping("/resume") public ResponseEntity<String> resume(@PathVariable UUID id, @PathVariable UUID taskId, @Valid @RequestBody Resume body, HttpServletRequest request) {
        noQuery(request); handling.authorizeTask(id, taskId);
        return idempotency.execute(request, HttpStatus.OK, () -> service.resume(id, taskId, body.expectedVersion(), body.answer(), body.acknowledgeUnknown()));
    }
    /** 撤销后不再调度，已在途响应不能覆盖撤销状态。 */
    @PostMapping("/cancel") public ResponseEntity<String> cancel(@PathVariable UUID id, @PathVariable UUID taskId, @Valid @RequestBody ExpenseHandlingController.Close body, HttpServletRequest request) {
        noQuery(request); handling.authorizeTask(id, taskId);
        return idempotency.execute(request, HttpStatus.OK, () -> service.cancel(id, taskId, body.expectedVersion()));
    }
    /**
     * HTTP 白名单在入口封闭，领域对象仅负责授权范围的不变量。
     * @author owlzhangfq@gmail.com
     */
    public record Scope(@NotNull java.util.List<Integer> policyLineNos, @NotNull java.util.List<UUID> invoiceIds,
            @NotNull java.util.List<UUID> precheckIds, @NotNull Integer maxSteps) {
        /** 转换时复用领域的数量、重复项及步数约束。 */
        public ExpenseAgentRun.Scope domain() { return new ExpenseAgentRun.Scope(policyLineNos, invoiceIds, precheckIds, maxSteps); }
        /** 防止静默忽略额外授权或任意工具参数。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown agent scope field"); }
    }
    private static void noQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw new DomainException("INVALID_AGENT_QUERY", "Agent does not accept query overrides"); }
    /**
     * 双摘要绑定已经展示的业务范围及模型目的地。
     * @author owlzhangfq@gmail.com
     */
    public record Start(@NotNull @Valid Scope scope, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String consentDigest,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String targetDigest) {
        /** 白名单之外的授权字段立即拒绝。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown agent start field"); }
    }
    /**
     * 未知调用续办要求明确布尔确认，不把缺少值当作同意。
     * @author owlzhangfq@gmail.com
     */
    public record Resume(@NotNull @Positive Long expectedVersion, @Size(max = 2000) String answer,
            @NotNull @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = InvoiceExtractionController.ConfirmationDeserializer.class) Boolean acknowledgeUnknown) {
        /** 回答不能包含任意工具或身份覆盖参数。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown agent resume field"); }
    }
}
