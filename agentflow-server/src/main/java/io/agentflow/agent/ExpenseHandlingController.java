package io.agentflow.agent;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 办理记录和受控只读工具入口，外部读准备与短事务登记分开。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/handling-tasks")
public class ExpenseHandlingController {
    private final ExpenseHandlingService service;
    private final IdempotencyExecutor idempotency;
    /** 成功回放继续检查本人身份，不创建第二个办理或步骤。 */
    public ExpenseHandlingController(ExpenseHandlingService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }
    /** 有界本人历史，关闭记录也保留原步骤。 */
    @GetMapping
    public ResponseEntity<List<ExpenseHandlingService.View>> list(@PathVariable UUID id, HttpServletRequest request) {
        noQuery(request); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id));
    }
    /** 本人明确目标后才开始持久办理记录。 */
    @PostMapping
    public ResponseEntity<String> start(@PathVariable UUID id, @Valid @RequestBody Start body, HttpServletRequest request) {
        noQuery(request); service.authorize(id);
        return idempotency.execute(request, HttpStatus.CREATED, () -> service.start(id, body.applicationVersion(), body.financialVersion(), body.goal()));
    }
    /** 结束记录不取消原已授权任务，不替代费用撤回。 */
    @PostMapping("/{taskId}/close")
    public ResponseEntity<String> close(@PathVariable UUID id, @PathVariable UUID taskId, @Valid @RequestBody Close body, HttpServletRequest request) {
        noQuery(request); service.authorize(id);
        return idempotency.execute(request, HttpStatus.OK, () -> service.close(id, taskId, body.expectedVersion()));
    }
    /** 服务器绑定身份和单据，只能执行四种已声明读取。 */
    @PostMapping("/{taskId}/inspect")
    public ResponseEntity<String> inspect(@PathVariable UUID id, @PathVariable UUID taskId, @Valid @RequestBody Inspect body, HttpServletRequest request) {
        noQuery(request); service.authorize(id);
        return idempotency.executePrepared(request, HttpStatus.OK,
                () -> service.prepare(id, taskId, body.expectedVersion(), body.tool(), body.referenceId(), body.lineNo()), prepared -> service.record(id, prepared));
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new DomainException("INVALID_AGENT_QUERY", "Expense handling does not accept query parameters");
    }
    /**
     * 目标说明只保存在办理记录，不默认成为模型外发材料。
     * @author owlzhangfq@gmail.com
     */
    public record Start(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion, @NotBlank @Size(max = 1000) String goal) {
        /** 未声明命令不能被静默忽略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown handling start field"); }
    }
    /**
     * 关闭必须核对当前办理版本。
     * @author owlzhangfq@gmail.com
     */
    public record Close(@NotNull @Positive Long expectedVersion) {
        /** 不接受批准或付款附加字段。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown handling close field"); }
    }
    /**
     * 工具特有参数只在入口对应分支校验，不接受租户或任意 URL。
     * @author owlzhangfq@gmail.com
     */
    public record Inspect(@NotNull @Positive Long expectedVersion, @NotNull ExpenseHandlingService.ReadTool tool, UUID referenceId, @Positive Integer lineNo) {
        /** 工具白名单之外的输入立即失败。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown handling tool field"); }
    }
}
