package io.agentflow.expense;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/**
 * 结构化费用接口不接受申请人、路由值、查验成功标识或核定金额的任意覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports")
public class ExpenseController {
    private final ExpenseDraftService drafts;
    private final IdempotencyExecutor idempotency;
    private final ExpenseSubmissionService submissions;
    private final ExpenseApprovalService approvals;
    private final ExpenseLifecycleService lifecycle;

    /** 财务写入继续使用平台请求幂等及实际认证主体。 */
    public ExpenseController(ExpenseDraftService drafts, IdempotencyExecutor idempotency, ExpenseSubmissionService submissions,
            ExpenseApprovalService approvals, ExpenseLifecycleService lifecycle) {
        this.drafts = drafts; this.idempotency = idempotency; this.submissions = submissions; this.approvals = approvals; this.lifecycle = lifecycle;
    }

    /** 创建草稿，只绑定可用的已发布费用流程。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateRequest request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.CREATED,
                () -> drafts.create(request.businessNo(), request.processKey(), request.definitionVersion(), request.content()));
    }

    /** 查询自己的当前草稿或某个有节点字段权限的历史轮次。 */
    @GetMapping("/{id}")
    public ExpenseResponse get(@PathVariable UUID id, @RequestParam(required = false) Integer roundNo) { return drafts.read(id, roundNo); }

    /** 完整替换当前费用内容，申请和财务版本必须同时匹配。 */
    @PostMapping("/{id}/revise")
    public ResponseEntity<String> revise(@PathVariable UUID id, @Valid @RequestBody ReviseRequest request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK,
                () -> drafts.revise(id, request.applicationVersion(), request.financialVersion(), request.content()));
    }

    /** 正式提交与重提共用实际预检、双版本和当前审批轮次。 */
    @PostMapping("/{id}/submit")
    public ResponseEntity<String> submit(@PathVariable UUID id, @Valid @RequestBody ExpenseSubmissionService.Input request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> submissions.submit(id, request));
    }

    /** 签收是明确操作，不隐含在通用审批同意中。 */
    @PostMapping("/{id}/tasks/{taskId}/receive")
    public ResponseEntity<String> receive(@PathVariable UUID id, @PathVariable String taskId,
            @Valid @RequestBody ExpenseApprovalService.ReceiveInput request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> approvals.receive(id, taskId, request));
    }

    /** 撤回保留本轮预留供补正。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(@PathVariable UUID id, @Valid @RequestBody ExpenseLifecycleService.Input request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> lifecycle.change(id, request, false));
    }

    /** 作废同时安排释放本地预留和已确认的外部预算。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID id, @Valid @RequestBody ExpenseLifecycleService.Input request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> lifecycle.change(id, request, true));
    }

    /**
     * 申请人和租户均从当前认证身份取得。
     * @author owlzhangfq@gmail.com
     */
    public record CreateRequest(@NotBlank @Size(max = 128) String businessNo, @NotBlank @Size(max = 128) String processKey,
                                @NotNull @Positive Long definitionVersion, @NotNull ExpenseContent content) { }

    /**
     * 两个独立版本阻止草稿修改覆盖并发审批或财务核减。
     * @author owlzhangfq@gmail.com
     */
    public record ReviseRequest(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion,
                                @NotNull ExpenseContent content) { }
}
