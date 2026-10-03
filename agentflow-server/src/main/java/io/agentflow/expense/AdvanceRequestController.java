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
import java.util.Map;
import java.util.UUID;

/**
 * 借款申请独立接口；调用方不能设置申请人、审批结果或到账余额。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/advance-requests")
public class AdvanceRequestController {
    private final AdvanceRequestService requests;
    private final AdvanceRequestQuery query;
    private final AdvanceRequestCheckService checks;
    private final AdvanceRequestSubmissionService submissions;
    private final IdempotencyExecutor idempotency;

    /** 外部查询仅排队，所有写接口复用现有幂等边界。 */
    public AdvanceRequestController(AdvanceRequestService requests, AdvanceRequestQuery query, AdvanceRequestCheckService checks,
            AdvanceRequestSubmissionService submissions, IdempotencyExecutor idempotency) {
        this.requests = requests; this.query = query; this.checks = checks; this.submissions = submissions; this.idempotency = idempotency;
    }

    /** 仅列本人借款申请，管理员不自动得到他人财务列表。 */
    @GetMapping
    public AdvanceRequestQuery.Page list(@RequestParam Map<String, String> parameters) { return query.list(parameters); }

    /** 创建不产生额度的借款申请草稿。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody Create request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.CREATED, () -> requests.create(request.businessNo(), request.processKey(), request.definitionVersion(), request.content()));
    }

    /** 按实际轮次权限读取草稿或冻结内容。 */
    @GetMapping("/{id}")
    public AdvanceRequestService.View get(@PathVariable UUID id, @RequestParam(required = false) Integer roundNo) { return requests.read(id, roundNo); }

    /** 同时核对审批和借款申请版本。 */
    @PostMapping("/{id}/revise")
    public ResponseEntity<String> revise(@PathVariable UUID id, @Valid @RequestBody Revise request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> requests.revise(id, request.applicationVersion(), request.requestVersion(), request.content()));
    }

    /** 显示此次预检会访问的实际财务目标。 */
    @GetMapping("/{id}/prechecks/options")
    public AdvanceRequestCheckService.Options options(@PathVariable UUID id) { return checks.options(id); }

    /** 历史状态与当前可用性分开读取。 */
    @GetMapping("/{id}/prechecks")
    public AdvanceRequestCheckService.Page prechecks(@PathVariable UUID id, @RequestParam Map<String, String> parameters) { return checks.list(id, parameters); }

    /** 只保存真实任职和版本，不在请求事务中访问外部目录。 */
    @PostMapping("/{id}/prechecks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody AdvanceRequestCheckService.QueueInput request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.ACCEPTED, () -> checks.queue(id, request));
    }

    /** 完整外部目录留在服务端，只返回本人候选借款申请。 */
    @GetMapping("/{id}/prechecks/{jobId}")
    public AdvanceRequestCheckService.View check(@PathVariable UUID id, @PathVariable UUID jobId) { return checks.get(id, jobId); }

    /** 实际提交固定借款申请、本人账户与任职，等待人工批准。 */
    @PostMapping("/{id}/submit")
    public ResponseEntity<String> submit(@PathVariable UUID id, @Valid @RequestBody AdvanceRequestSubmissionService.Input request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> submissions.submit(id, request));
    }

    /** 撤回后的新轮次重新获取目录和本人账户，不改写旧轮次。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(@PathVariable UUID id, @Valid @RequestBody Lifecycle request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> requests.change(id, request.applicationVersion(), request.requestVersion(), request.comment(), false));
    }

    /** 作废尚未批准的申请，已批准依据不能通过申请人作废入口撤销。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID id, @Valid @RequestBody Lifecycle request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> requests.change(id, request.applicationVersion(), request.requestVersion(), request.comment(), true));
    }

    /**
     * 申请身份来自认证，流程固定为已发布版本。
     * @author owlzhangfq@gmail.com
     */
    public record Create(@NotBlank @Size(max = 128) String businessNo, @NotBlank @Size(max = 128) String processKey,
                         @NotNull @Positive Long definitionVersion, @NotNull AdvanceRequestContent content) {
        /** 拒绝伪造业务状态和归属。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown advance request creation field"); }
    }

    /**
     * 完整替换草稿内容，两个版本缺一不可。
     * @author owlzhangfq@gmail.com
     */
    public record Revise(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotNull AdvanceRequestContent content) {
        /** 核定金额不能混入修改请求。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown advance request revision field"); }
    }

    /**
     * 生命周期动作保留原因和双版本。
     * @author owlzhangfq@gmail.com
     */
    public record Lifecycle(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 状态只能由指定业务动作决定。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown advance request lifecycle field"); }
    }
}
