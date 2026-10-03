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
 * 事前申请独立接口；调用方不能设置申请人、审批结果或可核销额度。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-plans")
public class ExpensePlanController {
    private final ExpensePlanService plans;
    private final ExpensePlanQuery query;
    private final ExpensePlanCheckService checks;
    private final ExpensePlanSubmissionService submissions;
    private final IdempotencyExecutor idempotency;

    /** 外部查询仅排队，所有写接口复用现有幂等边界。 */
    public ExpensePlanController(ExpensePlanService plans, ExpensePlanQuery query, ExpensePlanCheckService checks,
            ExpensePlanSubmissionService submissions, IdempotencyExecutor idempotency) {
        this.plans = plans; this.query = query; this.checks = checks; this.submissions = submissions; this.idempotency = idempotency;
    }

    /** 仅列本人计划，管理员不自动得到他人财务列表。 */
    @GetMapping
    public ExpensePlanQuery.Page list(@RequestParam Map<String, String> parameters) { return query.list(parameters); }

    /** 创建不产生额度的计划草稿。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody Create request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.CREATED, () -> plans.create(request.businessNo(), request.processKey(), request.definitionVersion(), request.content()));
    }

    /** 按实际轮次权限读取草稿或冻结内容。 */
    @GetMapping("/{id}")
    public ExpensePlanService.View get(@PathVariable UUID id, @RequestParam(required = false) Integer roundNo) { return plans.read(id, roundNo); }

    /** 同时核对审批和计划版本。 */
    @PostMapping("/{id}/revise")
    public ResponseEntity<String> revise(@PathVariable UUID id, @Valid @RequestBody Revise request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> plans.revise(id, request.applicationVersion(), request.planVersion(), request.content()));
    }

    /** 显示此次预检会访问的实际财务目标。 */
    @GetMapping("/{id}/prechecks/options")
    public ExpensePlanCheckService.Options options(@PathVariable UUID id) { return checks.options(id); }

    /** 历史状态与当前可用性分开读取。 */
    @GetMapping("/{id}/prechecks")
    public ExpensePlanCheckService.Page prechecks(@PathVariable UUID id, @RequestParam Map<String, String> parameters) { return checks.list(id, parameters); }

    /** 只保存真实任职和版本，不在请求事务中访问外部目录。 */
    @PostMapping("/{id}/prechecks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody ExpensePlanCheckService.QueueInput request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.ACCEPTED, () -> checks.queue(id, request));
    }

    /** 完整外部目录留在服务端，只返回本人候选计划。 */
    @GetMapping("/{id}/prechecks/{jobId}")
    public ExpensePlanCheckService.View check(@PathVariable UUID id, @PathVariable UUID jobId) { return checks.get(id, jobId); }

    /** 实际提交固定计划、汇率与任职，等待人工批准。 */
    @PostMapping("/{id}/submit")
    public ResponseEntity<String> submit(@PathVariable UUID id, @Valid @RequestBody ExpensePlanSubmissionService.Input request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> submissions.submit(id, request));
    }

    /** 撤回后的新轮次重新获取目录和汇率，不改写旧轮次。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(@PathVariable UUID id, @Valid @RequestBody Lifecycle request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> plans.change(id, request.applicationVersion(), request.planVersion(), request.comment(), false));
    }

    /** 作废尚未批准的申请，已批准额度不能通过申请人作废入口撤销。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID id, @Valid @RequestBody Lifecycle request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> plans.change(id, request.applicationVersion(), request.planVersion(), request.comment(), true));
    }

    /**
     * 申请身份来自认证，流程固定为已发布版本。
     * @author owlzhangfq@gmail.com
     */
    public record Create(@NotBlank @Size(max = 128) String businessNo, @NotBlank @Size(max = 128) String processKey,
                         @NotNull @Positive Long definitionVersion, @NotNull ExpensePlanContent content) {
        /** 拒绝伪造业务状态和归属。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense plan creation field"); }
    }

    /**
     * 完整替换草稿内容，两个版本缺一不可。
     * @author owlzhangfq@gmail.com
     */
    public record Revise(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long planVersion, @NotNull ExpensePlanContent content) {
        /** 核定金额不能混入修改请求。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense plan revision field"); }
    }

    /**
     * 生命周期动作保留原因和双版本。
     * @author owlzhangfq@gmail.com
     */
    public record Lifecycle(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long planVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 状态只能由指定业务动作决定。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense plan lifecycle field"); }
    }
}
