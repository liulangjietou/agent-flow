package io.agentflow.approval.comment;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;
import java.util.UUID;

/**
 * 评论入口复用申请详情授权，不能因评论作者或历史游标获得额外可见范围。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/comments")
public class ApplicationCommentController {
    private final ApprovalApplicationFacade applications;
    private final ApplicationCommentService comments;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 组合已有申请授权和独立评论存储。 */
    public ApplicationCommentController(ApprovalApplicationFacade applications, ApplicationCommentService comments,
                                        CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.applications = applications;
        this.comments = comments;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
    }

    /** 幂等回放也先复核当前申请可见性，评论不能保留已经失去的读取权限。 */
    @PostMapping
    public ResponseEntity<String> add(@PathVariable UUID id, @Valid @RequestBody AddCommentRequest request,
                                       HttpServletRequest httpRequest) {
        var application = applications.get(id);
        var actor = currentActor.actor();
        return idempotency.execute(httpRequest, HttpStatus.CREATED,
                () -> comments.add(actor, application, request.expectedVersion(), request.content().strip()));
    }

    /** 每次读取都重新校验当前访问权，包括使用旧游标翻页。 */
    @GetMapping
    public ResponseEntity<ApplicationCommentService.Page> list(@PathVariable UUID id, @RequestParam Map<String, String> raw) {
        var actor = currentActor.actor();
        var parameters = CommentQueryParameters.parse(actor, id, raw);
        var application = applications.get(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comments.list(application, parameters));
    }

    /**
     * 身份和时间只来自认证及服务端，客户端只提交正文和核对过的申请版本。
     * @author owlzhangfq@gmail.com
     */
    public record AddCommentRequest(@NotBlank @Size(max = 4000) String content,
                                     @NotNull @Positive Long expectedVersion) { }
}
