package io.agentflow.agent;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.agent.DraftSuggestion.Selection;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 申请人草稿建议入口，所有写操作使用原幂等执行器，读取禁止缓存。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/draft-assist-runs")
public class DraftAssistController {
    private final DraftAssistService service;
    private final IdempotencyExecutor idempotency;
    /** 业务授权与状态推进在应用服务内完成。 */
    public DraftAssistController(DraftAssistService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }

    /** 返回当前可发送的已保存字段和真实目的地，所有选项缺省不选。 */
    @GetMapping("/input")
    public ResponseEntity<DraftAssistService.InputOptions> input(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.input(id));
    }

    /** 分页返回本申请的建议索引，不接受申请人或租户覆盖参数。 */
    @GetMapping
    public ResponseEntity<JdbcDraftAssistRunRepository.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!Set.of("page", "pageSize").containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id, integer(raw.getFirst("page"), 0, 0, 10000),
                integer(raw.getFirst("pageSize"), 20, 1, 50)));
    }

    /** 原申请人读取模型原值、实际来源和人工确认结果。 */
    @GetMapping("/{runId}")
    public ResponseEntity<DraftAssistService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, runId));
    }

    /** 持久化明确授权的文本输入；请求内不接受模型地址或凭据。 */
    @PostMapping
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody GenerateRequest body, HttpServletRequest request) {
        return idempotency.execute(request, HttpStatus.ACCEPTED,
                () -> service.queue(id, body.expectedVersion(), body.targetDigest(), body.brief(), body.sourceIds()));
    }

    /** 采纳只修改草稿，丢弃不修改申请；两者均保留原模型建议。 */
    @PostMapping("/{runId}/review")
    public ResponseEntity<String> review(@PathVariable UUID id, @PathVariable UUID runId, @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        return idempotency.execute(request, HttpStatus.OK, () -> service.review(id, runId, body.expectedRunVersion(),
                body.expectedApplicationVersion(), body.action(), body.selected(), body.comment()));
    }
    private static int integer(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!value.matches("0|[1-9][0-9]{0,4}")) throw invalid();
        int number = Integer.parseInt(value);
        if (number < minimum || number > maximum) throw invalid();
        return number;
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_QUERY", "Invalid draft assist query"); }
    /**
     * 新说明与可发送来源由申请人明确提交，应用服务冻结原申请版本。
     * @author owlzhangfq@gmail.com
     */
    public record GenerateRequest(@NotNull @Positive Long expectedVersion, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String targetDigest,
                                  @NotBlank @Size(max = DraftAssistInput.MAX_BRIEF_LENGTH) String brief,
                                  @NotNull @Size(max = AssistInput.MAX_REFERENCES - 1) List<@NotBlank @Size(max = 150) String> sourceIds) { }
    /**
     * 人工选择保持有界，版本由申请聚合和建议聚合分别核对。
     * @author owlzhangfq@gmail.com
     */
    public record ReviewRequest(@NotNull @Positive Long expectedRunVersion, @Positive Long expectedApplicationVersion,
                                @NotNull AssistExecutionService.ReviewAction action,
                                @Size(max = DraftSuggestion.MAX_PROPOSALS) List<@NotNull Selection> selected,
                                @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) { }
}
