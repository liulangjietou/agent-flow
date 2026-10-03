package io.agentflow.agent;

import io.agentflow.api.idempotency.IdempotencyExecutor;
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
 * 本人费用预检解释入口，显式选择发送来源，复核只记录人工确认。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/precheck-explanations")
public class PrecheckExplanationController {
    private final PrecheckExplanationService service;
    private final IdempotencyExecutor idempotency;
    /** 请求幂等和本人权限沿用平台执行器，业务时效仍由应用服务判断。 */
    public PrecheckExplanationController(PrecheckExplanationService service, IdempotencyExecutor idempotency) {
        this.service = service; this.idempotency = idempotency;
    }

    /** 只能查询一个明确的原检查，不接受人员、租户或模型目的地覆盖。 */
    @GetMapping("/input")
    public ResponseEntity<PrecheckExplanationService.InputOptions> input(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.keySet().equals(Set.of("precheckId")) || raw.get("precheckId").size() != 1) throw invalid();
        UUID precheck;
        try {
            String value = raw.getFirst("precheckId"); precheck = UUID.fromString(value);
            if (!precheck.toString().equals(value)) throw invalid();
        } catch (IllegalArgumentException malformed) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.input(id, precheck));
    }

    /** 有界历史只返回状态索引，原输入在本人详情中读取。 */
    @GetMapping
    public ResponseEntity<JdbcPrecheckExplanationRepository.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!Set.of("page", "pageSize").containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id,
                integer(raw.getFirst("page"), 0, 0, 10000), integer(raw.getFirst("pageSize"), 20, 1, 50)));
    }

    /** 解释保留原检查结论，当前不可采纳原因单独返回。 */
    @GetMapping("/{runId}")
    public ResponseEntity<PrecheckExplanationService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, runId));
    }

    /** 202 只确认排队；请求不能带入自造预检结果、模型文本或金额写入。 */
    @PostMapping
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody GenerateRequest body, HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        service.authorize(id);
        return idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, body.precheckId(), body.applicationVersion(),
                body.financialVersion(), body.targetDigest(), body.sourceIds()));
    }

    /** 原键回放不会再次生成或写入复核，采纳不推进申请状态。 */
    @PostMapping("/{runId}/review")
    public ResponseEntity<String> review(@PathVariable UUID id, @PathVariable UUID runId, @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        service.authorize(id);
        return idempotency.execute(request, HttpStatus.OK, () -> service.review(id, runId, body.expectedRunVersion(), body.action(), body.selectedIssueIds(), body.comment()));
    }
    private static int integer(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!value.matches("0|[1-9][0-9]{0,4}")) throw invalid();
        int result = Integer.parseInt(value); if (result < minimum || result > maximum) throw invalid(); return result;
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_QUERY", "Invalid precheck explanation query"); }

    /**
     * 双版本和检查标识绑定已保存事实，来源正文由服务端读取。
     * @author owlzhangfq@gmail.com
     */
    public record GenerateRequest(@NotNull UUID precheckId, @NotNull @Positive Long applicationVersion,
            @NotNull @Positive Long financialVersion, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String targetDigest,
            @NotNull @Size(min = 1, max = AssistInput.MAX_REFERENCES) List<@NotBlank @Size(max = 150) String> sourceIds) {
        /** 不接受权威结论、输入正文或目的地 URL 等额外字段。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown precheck explanation request field"); }
    }
    /**
     * 人工只选择原问题标识，不能用复核接口修改费用内容。
     * @author owlzhangfq@gmail.com
     */
    public record ReviewRequest(@NotNull @Positive Long expectedRunVersion, @NotNull AssistExecutionService.ReviewAction action,
            @Size(max = PrecheckExplanationInput.MAX_ISSUES) List<@NotBlank @Size(max = 150) String> selectedIssueIds,
            @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) {
        /** 字段赋值和审批动作不属于解释复核请求。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown precheck explanation review field"); }
    }
}
