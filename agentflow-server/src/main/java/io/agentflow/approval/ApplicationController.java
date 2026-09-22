package io.agentflow.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 审批申请 REST 接口。 */
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

    /** 提交申请。 */
    @PostMapping("/{id}/submit")
    public ApplicationResponse submit(@PathVariable UUID id, @Valid @RequestBody SubmitApplicationRequest request) {
        return ApplicationResponse.from(facade.submit(id, request.expectedVersion()));
    }

    /** 创建申请请求。 */
    public record CreateApplicationRequest(@NotBlank String businessNo, @NotBlank String processKey,
                                           @NotNull Long definitionVersion, @NotBlank String title,
                                           Map<String, Object> payload) { }
    /** 提交申请请求。 */
    public record SubmitApplicationRequest(@NotNull Long expectedVersion) { }
}
