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
import java.util.List;
import java.util.HashSet;
import jakarta.validation.constraints.AssertTrue;

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
    private final CommentMentionDirectory mentions;

    /** 组合已有申请授权和独立评论存储。 */
    public ApplicationCommentController(ApprovalApplicationFacade applications, ApplicationCommentService comments,
                                        CurrentActor currentActor, IdempotencyExecutor idempotency, CommentMentionDirectory mentions) {
        this.applications = applications;
        this.comments = comments;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
        this.mentions = mentions;
    }

    /** 幂等回放也先复核当前申请可见性，评论不能保留已经失去的读取权限。 */
    @PostMapping
    public ResponseEntity<String> add(@PathVariable UUID id, @Valid @RequestBody AddCommentRequest request,
                                       HttpServletRequest httpRequest) {
        var application = applications.get(id);
        var actor = currentActor.actor();
        return idempotency.execute(httpRequest, HttpStatus.CREATED,
                () -> comments.add(actor, application, request.expectedVersion(), request.content().strip(), request.mentions()));
    }

    /** 名单读取不发送提醒，提交评论时仍重新核对最新接收资格。 */
    @GetMapping("/mention-options")
    public ResponseEntity<CommentMentionDirectory.Page> mentionOptions(@PathVariable UUID id, @RequestParam Map<String, String> raw) {
        var query = CommentMentionDirectory.Query.parse(raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(mentions.page(applications.get(id), currentActor.actor(), query));
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
     * 身份和时间来自认证及服务端；提醒选择是显式账号清单，正文中的 @ 不自动外发。
     * @author owlzhangfq@gmail.com
     */
    public record AddCommentRequest(@NotBlank @Size(max = 4000) String content,
                                     @NotNull @Positive Long expectedVersion,
                                     @Size(max = CommentMentionDirectory.MAX_MENTIONS) List<@NotBlank @Size(max = 128) String> mentions) {
        /** 兼容没有提醒清单的旧客户端。 */
        public AddCommentRequest { if (mentions == null) mentions = List.of(); }
        /** 同一人一次提醒，重复输入直接在入口拒绝。 */
        @AssertTrue public boolean isMentionSelectionUnique() { return new HashSet<>(mentions).size() == mentions.size(); }
    }
}
