package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.agent.ExpenseDraftAssistRun.Part;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/**
 * 本人报销填报建议入口；预览只读，排队和人工选择采用原幂等事务执行器。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/draft-assists")
public class ExpenseDraftAssistController {
    private final ExpenseDraftAssistService service;
    private final ExpenseDraftAssistPreparation preparation;
    private final IdempotencyExecutor idempotency;
    private final ExpenseHandlingChildren children;

    /** 外部目录准备与写入分开，成功回放不再请求目录或模型。 */
    public ExpenseDraftAssistController(ExpenseDraftAssistService service, ExpenseDraftAssistPreparation preparation, IdempotencyExecutor idempotency, ExpenseHandlingChildren children) {
        this.service = service; this.preparation = preparation; this.idempotency = idempotency;
        this.children = children;
    }

    /** 显示实际发送内容、目的地、有效期和确认摘要，不排队、不调用模型。 */
    @PostMapping("/preview")
    public ResponseEntity<ExpenseDraftAssistPreparation.Preview> preview(@PathVariable UUID id, @Valid @RequestBody InputRequest body, HttpServletRequest request) {
        noQuery(request); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preparation.prepare(id, body.command(), null).preview());
    }

    /** 202 仅表示排队；预览改变、目录失权或版本变化均要求重新确认发送内容。 */
    @PostMapping
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody GenerateRequest body, HttpServletRequest request) {
        noQuery(request); service.authorize(id);
        return idempotency.executePrepared(request, HttpStatus.ACCEPTED,
                () -> preparation.prepare(id, body.input().command(), body.validUntil()),
                prepared -> children.draft(id, prepared, body));
    }

    /** 历史分页不接受人员、租户、模型目标或任意条件覆盖。 */
    @GetMapping
    public ResponseEntity<JdbcExpenseDraftAssistRepository.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!Set.of("page", "pageSize").containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id,
                integer(raw.getFirst("page"), 0, 0, 10000), integer(raw.getFirst("pageSize"), 20, 1, 50)));
    }

    /** 原始来源和模型建议只返回当前本人，管理员身份不能替代报销归属。 */
    @GetMapping("/{runId}")
    public ResponseEntity<ExpenseDraftAssistService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw invalid(); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, runId));
    }

    /** 逐项确认前重新核对目录；该回执不表示已保存费用，也不产生审批动作。 */
    @PostMapping("/{runId}/confirm")
    public ResponseEntity<String> confirm(@PathVariable UUID id, @PathVariable UUID runId, @Valid @RequestBody ConfirmRequest body, HttpServletRequest request) {
        noQuery(request); service.authorize(id);
        return idempotency.executePrepared(request, HttpStatus.OK,
                () -> preparation.refresh(service.reviewContext(id, runId, body.expectedRunVersion(), body.applicationVersion(), body.financialVersion())),
                prepared -> service.confirm(id, runId, body.expectedRunVersion(), prepared, body.selected().stream().map(SelectionRequest::selection).toList(), body.comment()));
    }

    /** 放弃旧建议不读取外部目录，幂等回放保留原结果。 */
    @PostMapping("/{runId}/dismiss")
    public ResponseEntity<String> dismiss(@PathVariable UUID id, @PathVariable UUID runId, @Valid @RequestBody DismissRequest body, HttpServletRequest request) {
        noQuery(request); service.authorize(id);
        return idempotency.execute(request, HttpStatus.OK, () -> service.dismiss(id, runId, body.expectedRunVersion(), body.comment()));
    }
    private static void noQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw invalid(); }
    private static int integer(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback; if (!value.matches("0|[1-9][0-9]{0,4}")) throw invalid();
        int result = Integer.parseInt(value); if (result < minimum || result > maximum) throw invalid(); return result;
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_QUERY", "Invalid expense draft assistance query"); }
    private static void unknown() { throw new IllegalArgumentException("Unknown expense draft assistance request field"); }

    /**
     * 本人输入不接受模型结果、金额或财务事实覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record InputRequest(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion,
            @NotBlank @Size(max = DraftAssistInput.MAX_BRIEF_LENGTH) String brief,
            @NotNull @Size(min = 1, max = ExpenseDraftAssistInput.MAX_LEGS) List<@NotNull @Valid LegRequest> itinerary,
            @NotNull @Valid CatalogRequest catalog) {
        /** 转为服务端类型，业务不变量由原行程及目录选择对象维护。 */
        public ExpenseDraftAssistPreparation.Request command() {
            return new ExpenseDraftAssistPreparation.Request(applicationVersion, financialVersion, brief,
                    itinerary.stream().map(LegRequest::leg).toList(), new ExpenseDraftAssistInputs.CatalogSelection(catalog.categoryCodes(), catalog.costCenterCodes(), catalog.projectCodes()));
        }
        /** 拒绝额外字段，避免将传输层容错误解为支持写入。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    /**
     * 行程只接受明确日期、城市和用途，不包含模型可以修改的财务金额。
     * @author owlzhangfq@gmail.com
     */
    public record LegRequest(@NotNull @Min(1) @Max(ExpenseDraftAssistInput.MAX_LEGS) Integer id,
            @NotNull LocalDate startsOn, @NotNull LocalDate endsOn, @NotBlank @Size(max = 128) String cityCode,
            @NotBlank @Size(max = 2000) String purpose) {
        /** 日期顺序等领域约束沿用行程值对象。 */
        public ExpenseDraftAssistInput.Leg leg() { return new ExpenseDraftAssistInput.Leg(id, startsOn, endsOn, cityCode, purpose); }
        /** 目录显示名及金额不能由行程请求注入。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    /**
     * 仅传所选代码，真实名称和法人归属在本人财务目录中校验。
     * @author owlzhangfq@gmail.com
     */
    public record CatalogRequest(@NotNull @Size(min = 1, max = ExpenseDraftAssistInput.MAX_OPTIONS) List<@NotBlank @Size(max = 128) String> categoryCodes,
            @NotNull @Size(min = 1, max = ExpenseDraftAssistInput.MAX_OPTIONS) List<@NotBlank @Size(max = 128) String> costCenterCodes,
            @NotNull @Size(max = ExpenseDraftAssistInput.MAX_OPTIONS) List<@NotBlank @Size(max = 128) String> projectCodes) {
        /** 完整目录和任意员工标识不属于客户端选择参数。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    /**
     * 原内容、有效期及双摘要绑定已经展示的发送清单。
     * @author owlzhangfq@gmail.com
     */
    public record GenerateRequest(@NotNull @Valid InputRequest input, @NotNull Instant validUntil,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String targetDigest,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String consentDigest, UUID handlingTaskId) {
        /** 兼容独立费用草稿请求。 */
        public GenerateRequest(InputRequest input, Instant validUntil, String targetDigest, String consentDigest) {
            this(input, validUntil, targetDigest, consentDigest, null);
        }
        /** 不接受模型目的地 URL、凭据或未展示的来源正文。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    /**
     * 申请人只选择原建议的字段组，不能借确认接口替换建议值。
     * @author owlzhangfq@gmail.com
     */
    public record SelectionRequest(@NotBlank @Size(max = 64) String proposalId,
            @NotNull @Size(min = 1, max = 3) Set<@NotNull Part> parts) {
        /** 必须明确确认新建行程，类别和分摊保持独立选择。 */
        public ExpenseDraftAssistRun.Selection selection() { return new ExpenseDraftAssistRun.Selection(proposalId, parts); }
        /** 金额、字段赋值及批准动作不属于人工选择。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    /**
     * 双版本对应原报销和申请，运行版本单独进行并发控制。
     * @author owlzhangfq@gmail.com
     */
    public record ConfirmRequest(@NotNull @Positive Long expectedRunVersion, @NotNull @Positive Long applicationVersion,
            @NotNull @Positive Long financialVersion,
            @NotNull @Size(min = 1, max = ExpenseDraftSuggestion.MAX_LINES) List<@NotNull @Valid SelectionRequest> selected,
            @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) {
        /** 确认不会接收完整费用正文。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    /**
     * 放弃只记录原运行版本和本人意见，不要求旧目录仍有效。
     * @author owlzhangfq@gmail.com
     */
    public record DismissRequest(@NotNull @Positive Long expectedRunVersion, @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) {
        /** 放弃请求不能夹带确认或写入字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
}
