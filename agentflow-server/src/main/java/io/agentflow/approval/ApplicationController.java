package io.agentflow.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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

    /** 创建控制器。 */
    public ApplicationController(ApprovalApplicationFacade facade) {
        this.facade = facade;
    }

    /** 创建申请草稿。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse create(@Valid @RequestBody CreateApplicationRequest request) {
        return ApplicationResponse.from(facade.create(request.businessNo(), request.processKey(),
                request.definitionVersion(), request.title(), request.payload()));
    }

    /** 查询当前租户申请列表。 */
    @GetMapping
    public List<ApplicationResponse> list() {
        return facade.list().stream().map(ApplicationResponse::from).toList();
    }

    /** 查询申请详情。 */
    @GetMapping("/{id}")
    public ApplicationResponse get(@PathVariable UUID id) {
        return ApplicationResponse.from(facade.get(id));
    }

    /** 修改草稿或退回后的申请内容，不改变绑定定义与历史轮次。 */
    @PutMapping("/{id}")
    public ApplicationResponse revise(@PathVariable UUID id, @Valid @RequestBody ReviseApplicationRequest request) {
        return ApplicationResponse.from(facade.revise(id, request.expectedVersion(), request.title(), request.payload()));
    }

    /** 查询有权访问的申请的全部提交轮次。 */
    @GetMapping("/{id}/rounds")
    public List<SubmissionRoundResponse> rounds(@PathVariable UUID id) {
        return facade.rounds(id).stream().map(SubmissionRoundResponse::from).toList();
    }

    /** 提交申请。 */
    @PostMapping("/{id}/submit")
    public ApplicationResponse submit(@PathVariable UUID id, @Valid @RequestBody SubmitApplicationRequest request) {
        return ApplicationResponse.from(facade.submit(id, request.expectedVersion()));
    }

    /** 发起人撤回当前审批轮次。 */
    @PostMapping("/{id}/withdraw")
    public ApplicationResponse withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawApplicationRequest request) {
        return ApplicationResponse.from(facade.withdraw(id, request.expectedVersion(), request.comment()));
    }

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
    public record SubmitApplicationRequest(@NotNull Long expectedVersion) { }

    /**
     * 申请补正请求；业务号、流程标识与定义版本不属于可修改字段。
     * @author owlzhangfq@gmail.com
     */
    public record ReviseApplicationRequest(@NotNull Long expectedVersion, @NotBlank @Size(max = 256) String title,
                                           @NotNull Map<String, Object> payload) { }
}
