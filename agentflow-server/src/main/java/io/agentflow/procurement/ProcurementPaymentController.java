package io.agentflow.procurement;

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
 * 采购付款申请独立接口；调用方不能设置申请人、审批结果或原应付余额。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/procurement-payments")
public class ProcurementPaymentController {
    private final ProcurementPaymentService requests;
    private final ProcurementPaymentQuery query;
    private final ProcurementPaymentCheckService checks;
    private final ProcurementPaymentSubmissionService submissions;
    private final IdempotencyExecutor idempotency;

    /** 外部查询仅排队，所有写接口复用现有幂等边界。 */
    public ProcurementPaymentController(ProcurementPaymentService requests, ProcurementPaymentQuery query, ProcurementPaymentCheckService checks,
            ProcurementPaymentSubmissionService submissions, IdempotencyExecutor idempotency) {
        this.requests = requests; this.query = query; this.checks = checks; this.submissions = submissions; this.idempotency = idempotency;
    }

    /** 仅列本人采购付款申请，管理员不自动得到他人财务列表。 */
    @GetMapping
    public ProcurementPaymentQuery.Page list(@RequestParam Map<String, String> parameters) { return query.list(parameters); }

    /** 创建只包含付款意图的采购申请草稿。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody Create request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.CREATED, () -> requests.create(request.businessNo(), request.processKey(), request.definitionVersion(), request.content()));
    }

    /** 按实际轮次权限读取草稿或冻结内容。 */
    @GetMapping("/{id}")
    public ProcurementPaymentService.View get(@PathVariable UUID id, @RequestParam(required = false) Integer roundNo) { return requests.read(id, roundNo); }

    /** 同时核对审批和采购付款申请版本。 */
    @PostMapping("/{id}/revise")
    public ResponseEntity<String> revise(@PathVariable UUID id, @Valid @RequestBody Revise request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> requests.revise(id, request.applicationVersion(), request.requestVersion(), request.content()));
    }

    /** 显示此次预检会访问的实际财务目标。 */
    @GetMapping("/{id}/prechecks/options")
    public ProcurementPaymentCheckService.Options options(@PathVariable UUID id) { return checks.options(id); }

    /** 历史状态与当前可用性分开读取。 */
    @GetMapping("/{id}/prechecks")
    public ProcurementPaymentCheckService.Page prechecks(@PathVariable UUID id, @RequestParam Map<String, String> parameters) { return checks.list(id, parameters); }

    /** 只保存真实任职和版本，不在请求事务中访问外部目录。 */
    @PostMapping("/{id}/prechecks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody ProcurementPaymentCheckService.QueueInput request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.ACCEPTED, () -> checks.queue(id, request));
    }

    /** 完整外部目录留在服务端，只返回本人候选采购付款申请。 */
    @GetMapping("/{id}/prechecks/{jobId}")
    public ProcurementPaymentCheckService.View check(@PathVariable UUID id, @PathVariable UUID jobId) { return checks.get(id, jobId); }

    /** 实际提交固定采购付款申请、供应商账户与任职，等待人工批准。 */
    @PostMapping("/{id}/submit")
    public ResponseEntity<String> submit(@PathVariable UUID id, @Valid @RequestBody ProcurementPaymentSubmissionService.Input request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> submissions.submit(id, request));
    }

    /** 撤回后的新轮次重新获取目录和原供应商应付，不改写旧轮次。 */
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
                         @NotNull @Positive Long definitionVersion, @NotNull ProcurementPaymentContent content) {
        /** 拒绝伪造业务状态和归属。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown procurement payment creation field"); }
    }

    /**
     * 完整替换草稿内容，两个版本缺一不可。
     * @author owlzhangfq@gmail.com
     */
    public record Revise(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotNull ProcurementPaymentContent content) {
        /** 核定金额不能混入修改请求。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown procurement payment revision field"); }
    }

    /**
     * 生命周期动作保留原因和双版本。
     * @author owlzhangfq@gmail.com
     */
    public record Lifecycle(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 状态只能由指定业务动作决定。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown procurement payment lifecycle field"); }
    }
}
