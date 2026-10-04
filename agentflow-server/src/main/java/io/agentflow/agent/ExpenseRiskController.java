package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseRiskEvidence;
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
 * 当前费用审批的风险提示入口；客户端选择现有来源位置，不提供身份、金额事实或模型正文。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/risk-explanations")
public class ExpenseRiskController {
    private final ExpenseRiskService service;
    private final IdempotencyExecutor idempotency;

    /** 回执幂等与业务事务沿用平台执行器；当前读取权限在回放前检查。 */
    public ExpenseRiskController(ExpenseRiskService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }

    /** 当前决定人读取可选工作日历摘要；不会默认选择周末规则或替代财务制度。 */
    @GetMapping("/calendars")
    public ResponseEntity<ExpenseRiskAccess.CalendarOptions> calendars(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!Set.of("roundNo", "taskId", "afterKey").containsAll(raw.keySet()) || !raw.containsKey("roundNo")
                || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        String taskId = raw.getFirst("taskId"), afterKey = raw.getFirst("afterKey");
        if (org.apache.commons.lang3.StringUtils.isBlank(taskId) || taskId.length() > 64
                || afterKey != null && !afterKey.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.calendarOptions(id,
                integer(raw.getFirst("roundNo"), 0, 1, Integer.MAX_VALUE), taskId, afterKey));
    }

    /** 复杂范围使用只读 POST 预览，不建立运行、不发送模型、不写幂等记录。 */
    @PostMapping("/input")
    public ResponseEntity<ExpenseRiskService.InputOptions> input(@PathVariable UUID id, @Valid @RequestBody InputRequest body, HttpServletRequest request) {
        rejectQuery(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.input(id, body.taskId(), body.scope().selection()));
    }

    /** 主单原轮次必须明确给出，分页有界且不接受重复或未知查询参数。 */
    @GetMapping
    public ResponseEntity<JdbcExpenseRiskRepository.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!Set.of("roundNo", "page", "pageSize").containsAll(raw.keySet()) || !raw.containsKey("roundNo")
                || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id,
                integer(raw.getFirst("roundNo"), 0, 1, Integer.MAX_VALUE), integer(raw.getFirst("page"), 0, 0, 10000),
                integer(raw.getFirst("pageSize"), 20, 1, 50)));
    }

    /** 正文每次逐单授权，当前能否复核和能否采纳分别表达。 */
    @GetMapping("/{runId}")
    public ResponseEntity<ExpenseRiskService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, runId));
    }

    /** 202 仅确认排队；原登录引用从当前认证请求捕获，不能由正文覆盖。 */
    @PostMapping
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody GenerateRequest body, HttpServletRequest request) {
        rejectQuery(request); var selection = body.scope().selection(); service.authorizeSelection(id, selection);
        return idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, body.taskId(), selection,
                body.inputDigest(), body.targetDigest(), body.sourceIds(), request));
    }

    /** 采纳或放弃只记录原提示的人工复核，重放不能再次写入轨迹。 */
    @PostMapping("/{runId}/review")
    public ResponseEntity<String> review(@PathVariable UUID id, @PathVariable UUID runId, @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        rejectQuery(request); service.authorizeRun(id, runId);
        return idempotency.execute(request, HttpStatus.OK, () -> service.review(id, runId, body.expectedRunVersion(), body.action(), body.selectedConcernIds(), body.comment()));
    }

    private static void rejectQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw invalid(); }
    private static int integer(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!value.matches("0|[1-9][0-9]{0,9}")) throw invalid();
        try { int parsed = Integer.parseInt(value); if (parsed < minimum || parsed > maximum) throw invalid(); return parsed; }
        catch (NumberFormatException malformed) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_QUERY", "Invalid expense risk query"); }
    private static IllegalArgumentException unknown() { return new IllegalArgumentException("Unknown expense risk request field"); }

    /**
     * 单据来源位置只接受现有主键、原轮次和明确行号，任何额外字段都拒绝。
     * @author owlzhangfq@gmail.com
     */
    public record DocumentRequest(@NotNull UUID reportId, @NotNull @Positive Integer roundNo,
                                  @NotNull @Size(min = 1, max = ExpenseContent.MAX_LINES) List<@NotNull @Positive Integer> lineNos) {
        /** 不接受伪造票面或金额。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 第一份是当前主单，其他是操作者明确选择的对照单；日历为空时不推断工作日。
     * @author owlzhangfq@gmail.com
     */
    public record ScopeRequest(@NotNull @Size(min = 1, max = ExpenseRiskEvidence.MAX_DOCUMENTS) List<@NotNull @Valid DocumentRequest> documents, UUID calendarId) {
        /** 在来源入口校验跨单总数和重复位置。 */
        public ExpenseRiskAccess.Selection selection() {
            return new ExpenseRiskAccess.Selection(documents.stream().map(d -> new ExpenseRiskAccess.SelectedDocument(d.reportId(), d.roundNo(), d.lineNos())).toList(), calendarId);
        }
        /** 租户与跨单范围不能通过额外属性扩展。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 只读预览沿用当前任务资格，不带入客户端正文。
     * @author owlzhangfq@gmail.com
     */
    public record InputRequest(@NotBlank @Size(max = 64) String taskId, @NotNull @Valid ScopeRequest scope) {
        /** 模型目的地只来自部署配置。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 摘要与所选来源共同表达这次发送确认，服务端重建所有事实。
     * @author owlzhangfq@gmail.com
     */
    public record GenerateRequest(@NotBlank @Size(max = 64) String taskId, @NotNull @Valid ScopeRequest scope,
                                  @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String inputDigest,
                                  @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String targetDigest,
                                  @NotNull @Size(min = 1, max = AssistInput.MAX_REFERENCES) List<@NotBlank @Size(max = 150) String> sourceIds) {
        /** 登录引用、模型输出与业务金额不属于发送请求。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 人工仅选择原解释和填写意见，不提供审批动作或费用赋值。
     * @author owlzhangfq@gmail.com
     */
    public record ReviewRequest(@NotNull @Positive Long expectedRunVersion, @NotNull AssistExecutionService.ReviewAction action,
                                @Size(max = ExpenseRiskInput.MAX_CONCERNS) List<@NotBlank @Size(max = 150) String> selectedConcernIds,
                                @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) {
        /** 未声明的写入意图直接拒绝。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
}
