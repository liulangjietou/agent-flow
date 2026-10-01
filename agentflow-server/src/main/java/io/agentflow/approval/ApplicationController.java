package io.agentflow.approval;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审批申请 REST 接口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private final ApprovalApplicationFacade facade;
    private final IdempotencyExecutor idempotency;
    private final ApplicationFieldViews fields;

    /** 创建控制器。 */
    public ApplicationController(ApprovalApplicationFacade facade, IdempotencyExecutor idempotency, ApplicationFieldViews fields) {
        this.facade = facade;
        this.idempotency = idempotency;
        this.fields = fields;
    }

    /** 创建申请草稿。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateApplicationRequest request, HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.CREATED, () -> ApplicationResponse.from(facade.create(
                request.businessNo(), request.processKey(), request.definitionVersion(), request.title(), request.payload())));
    }

    /** 查询当前租户申请列表。 */
    @GetMapping
    public List<ApplicationResponse> list() {
        return facade.list().stream().map(fields::application).toList();
    }

    /** 查询申请详情。 */
    @GetMapping("/{id}")
    public ApplicationResponse get(@PathVariable UUID id) {
        return fields.application(facade.get(id));
    }

    /** 查询申请原绑定版本的任职要求；重提仍按该版本固定新的轮次任职。 */
    @GetMapping("/{id}/initiator-requirements")
    public ResponseEntity<io.agentflow.definition.DefinitionInitiatorRequirements.View> initiatorRequirements(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(facade.initiatorRequirements(id));
    }

    /** 修改草稿或退回后的申请内容，不改变绑定定义与历史轮次。 */
    @PutMapping("/{id}")
    public ResponseEntity<String> revise(@PathVariable UUID id, @Valid @RequestBody ReviseApplicationRequest request,
                                         HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> ApplicationResponse.from(facade.revise(id, request.expectedVersion(), request.title(), request.payload())));
    }

    /** 查询有权访问的申请的全部提交轮次。 */
    @GetMapping("/{id}/rounds")
    public List<SubmissionRoundResponse> rounds(@PathVariable UUID id) {
        var application = facade.get(id);
        return facade.rounds(id).stream().map(round -> fields.round(application, round)).toList();
    }

    /** 提交申请。 */
    @PostMapping("/{id}/submit")
    public ResponseEntity<String> submit(@PathVariable UUID id, @Valid @RequestBody SubmitApplicationRequest request,
                                         HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK, () -> ApplicationResponse.from(
                facade.submit(id, request.expectedVersion(), request.initiatorAppointmentId())));
    }

    /** 发起人撤回当前审批轮次。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawApplicationRequest request,
                                           HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> ApplicationResponse.from(facade.withdraw(id, request.expectedVersion(), request.comment())));
    }

    /** 作废草稿、已退回或已撤回的申请，保留记录且不能再提交。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID id, @Valid @RequestBody CancelApplicationRequest request,
                                         HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> ApplicationResponse.from(facade.cancel(id, request.expectedVersion(), request.comment())));
    }

    /**
     * 作废说明选填，版本必填；不能携带待保存的表单内容或其他租户。
     * @author owlzhangfq@gmail.com
     */
    public record CancelApplicationRequest(@NotNull Long expectedVersion, @Size(max = 2000) String comment) { }

    /**
     * 撤回说明选填；长度在接口边界约束。
     * @author owlzhangfq@gmail.com
     */
    public record WithdrawApplicationRequest(@NotNull Long expectedVersion, @Size(max = 2000) String comment) { }

    /**
     * 创建申请请求。
     * @author owlzhangfq@gmail.com
     */
    public record CreateApplicationRequest(@NotBlank String businessNo, @NotBlank String processKey,
                                           @NotNull Long definitionVersion, @NotBlank String title,
                                           Map<String, Object> payload) { }
    /**
     * 提交申请请求。
     * @author owlzhangfq@gmail.com
     */
    public record SubmitApplicationRequest(@NotNull Long expectedVersion, UUID initiatorAppointmentId) { }

    /**
     * 申请补正请求；业务号、流程标识与定义版本不属于可修改字段。
     * @author owlzhangfq@gmail.com
     */
    public record ReviseApplicationRequest(@NotNull Long expectedVersion, @NotBlank @Size(max = 256) String title,
                                           @NotNull Map<String, Object> payload) { }
}
