package io.agentflow.agent;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;
import java.util.List;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Agent 查询与执行入口；授权选择输入后排队，模型调用由事务外后台执行。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/assist-runs")
public class AssistRunController {
    private final ApprovalApplicationFacade applications;
    private final AssistRunQueryService queries;
    private final CurrentActor currentActor;
    private final AssistExecutionService execution;
    private final IdempotencyExecutor idempotency;

    /** 授权与运行查询分别由已有审批服务和 Agent 应用服务负责。 */
    public AssistRunController(ApprovalApplicationFacade applications, AssistRunQueryService queries, CurrentActor currentActor,
                               AssistExecutionService execution, IdempotencyExecutor idempotency) {
        this.applications = applications; this.queries = queries; this.currentActor = currentActor;
        this.execution = execution; this.idempotency = idempotency;
    }

    /** 每一页重新核对申请可见性，游标不会保留已撤销的权限。 */
    @GetMapping
    public ResponseEntity<AssistRunQueryService.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        var parameters = AssistRunQueryParameters.parse(currentActor.actor(), id, raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(applications.get(id), parameters));
    }

    /** 申请归属、租户和实时可见性同时约束详情，响应不进入浏览器缓存。 */
    @GetMapping("/{runId}")
    public ResponseEntity<AssistRunQueryService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId,
                                                          @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw new DomainException("INVALID_AGENT_QUERY", "Assist run detail does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.get(applications.get(id), runId));
    }

    /** 服务端生成可发送目录，不能由客户端指定正文或模型目的地。 */
    @GetMapping("/input")
    public ResponseEntity<AssistExecutionService.InputOptions> input(@PathVariable UUID id, @RequestParam String taskId) {
        if (taskId.isBlank() || taskId.length() > 128) throw new DomainException("INVALID_AGENT_QUERY", "A bounded task identifier is required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(execution.input(id, taskId));
    }

    /** 幂等创建持久任务，202 只表示排队，不暗示生成或审批成功。 */
    @PostMapping
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody GenerateRequest body, HttpServletRequest request) {
        return idempotency.execute(request, HttpStatus.ACCEPTED, () -> execution.queue(id, body.taskId(), body.expectedVersion(), body.targetDigest(), body.sourceIds()));
    }

    /** 每次复核要求当前可决策任务、当前申请版本和运行乐观版本。 */
    @PostMapping("/{runId}/review")
    public ResponseEntity<String> review(@PathVariable UUID id, @PathVariable UUID runId,
                                         @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        return idempotency.execute(request, HttpStatus.OK, () -> execution.review(id, runId, body.taskId(), body.expectedVersion(),
                body.expectedRunVersion(), body.action(), body.acceptedText(), body.comment()));
    }

    /**
     * 用户只能选择服务端来源，租户、主体、提示和模型均不可覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record GenerateRequest(@NotBlank @Size(max = 128) String taskId, @NotNull @Positive Long expectedVersion,
                                  @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{64}") String targetDigest,
                                  @NotEmpty @Size(max = AssistInput.MAX_REFERENCES) List<@NotBlank @Size(max = 150) String> sourceIds) { }

    /**
     * 复核保留模型原文，人工修订稿与复核说明各自有界。
     * @author owlzhangfq@gmail.com
     */
    public record ReviewRequest(@NotBlank @Size(max = 128) String taskId, @NotNull @Positive Long expectedVersion,
                                @NotNull @Positive Long expectedRunVersion, @NotNull AssistExecutionService.ReviewAction action,
                                @Size(max = AssistRun.MAX_REVIEW_TEXT_LENGTH) String acceptedText,
                                @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) { }
}
