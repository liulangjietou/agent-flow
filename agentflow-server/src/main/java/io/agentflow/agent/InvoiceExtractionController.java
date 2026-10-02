package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
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
 * 本人票据抽取入口；原件准备、请求回执和领域写入保持各自事务边界。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/invoices/{id}/extraction-runs")
public class InvoiceExtractionController {
    private final InvoiceExtractionService service;
    private final IdempotencyExecutor idempotency;
    /** 复用原身份、幂等和原件服务，不接受客户端代填归属或页数。 */
    public InvoiceExtractionController(InvoiceExtractionService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }

    /** 在事务外检查完整原件，展示实际处理方式和模型目的地，不发送或排队。 */
    @GetMapping("/input")
    public ResponseEntity<InvoiceExtractionService.InputOptions> input(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        requireNoQuery(raw); return noStore(service.input(id));
    }

    /** 本人历史只返回有界索引，不含原件内容、建议值或人工修订。 */
    @GetMapping
    public ResponseEntity<JdbcInvoiceExtractionRunRepository.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!Set.of("page", "pageSize").containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalidQuery();
        return noStore(service.list(id, integer(raw.getFirst("page"), 0, 0, 1_000_000), integer(raw.getFirst("pageSize"), 20, 1, 50)));
    }

    /** 当前本人权限下读取原始候选和独立的人工确认，管理员不能代读。 */
    @GetMapping("/{runId}")
    public ResponseEntity<InvoiceExtractionService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId, @RequestParam MultiValueMap<String, String> raw) {
        requireNoQuery(raw); return noStore(service.get(id, runId));
    }

    /** 明确外发确认必须与实际方式一致，成功回执只表示任务已经登记。 */
    @PostMapping
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody GenerateRequest body,
                                         @RequestParam MultiValueMap<String, String> raw, HttpServletRequest request) {
        requireNoQuery(raw);
        boolean model = body.method() == InvoiceExtractionSuggestion.Method.MODEL;
        if (model != body.externalSendConfirmed() || model != (body.targetDigest() != null)) {
            throw new DomainException("INVALID_AGENT_CONSENT", "Invoice extraction method and external transmission confirmation must match");
        }
        return noStore(idempotency.executePrepared(request, HttpStatus.ACCEPTED,
                () -> service.prepare(id, body.expectedOriginalId(), body.expectedOriginalDigest()),
                prepared -> service.queue(prepared, body.method(), body.targetDigest())));
    }

    /** 确认只保存本人选择的值，放弃保留历史；两者都不执行税务查验或修改财务状态。 */
    @PostMapping("/{runId}/review")
    public ResponseEntity<String> review(@PathVariable UUID id, @PathVariable UUID runId, @Valid @RequestBody ReviewRequest body,
                                          @RequestParam MultiValueMap<String, String> raw, HttpServletRequest request) {
        requireNoQuery(raw);
        var selected = body.selected() == null ? null : body.selected().stream().map(SelectionRequest::selection).toList();
        return noStore(idempotency.execute(request, HttpStatus.OK,
                () -> service.review(id, runId, body.expectedRunVersion(), body.action(), selected, body.comment())));
    }

    private static void requireNoQuery(MultiValueMap<String, String> raw) { if (!raw.isEmpty()) throw invalidQuery(); }
    private static int integer(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!value.matches("0|[1-9][0-9]{0,6}")) throw invalidQuery();
        int number = Integer.parseInt(value); if (number < minimum || number > maximum) throw invalidQuery(); return number;
    }
    private static DomainException invalidQuery() { return new DomainException("INVALID_AGENT_QUERY", "Invalid invoice extraction query"); }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<String> noStore(ResponseEntity<String> result) {
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }

    /**
     * 请求绑定已展示的原件与处理方式，不接受页数、模型地址或凭据。
     * @author owlzhangfq@gmail.com
     */
    public record GenerateRequest(@NotNull UUID expectedOriginalId, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedOriginalDigest,
                                  @NotNull InvoiceExtractionSuggestion.Method method, @Pattern(regexp = "[a-f0-9]{64}") String targetDigest,
                                  @NotNull @JsonDeserialize(using = ConfirmationDeserializer.class) Boolean externalSendConfirmed) {
        /** 防止额外字段被静默忽略成已接受的配置或权限。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown invoice extraction request field"); }
    }
    /**
     * 人工票面值必须是字符串，防止 JSON 数值先丢失票号前导零或金额精度。
     * @author owlzhangfq@gmail.com
     */
    public record SelectionRequest(@NotNull InvoiceExtractionSuggestion.Field field, @NotNull Object value) {
        /** 在 JSON 入口保留原始类型，不允许数字被自动转换成字符串。 */
        public SelectionRequest { if (!(value instanceof String)) throw new IllegalArgumentException("Invoice field value must be a string"); }
        /** 进入应用服务前转换为经过领域格式校验的人工值。 */
        public InvoiceExtractionSuggestion.Selection selection() { return new InvoiceExtractionSuggestion.Selection(field, (String) value); }
        /** 人工值不接收原件证据或查验结论的覆盖字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown invoice extraction selection field"); }
    }
    /**
     * 明确版本与人工动作，原候选和人工修订分别保存。
     * @author owlzhangfq@gmail.com
     */
    public record ReviewRequest(@NotNull @Positive @JsonDeserialize(using = RunVersionDeserializer.class) Long expectedRunVersion,
                                @NotNull InvoiceExtractionService.ReviewAction action,
                                List<@NotNull @Valid SelectionRequest> selected,
                                @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment) {
        /** 字段数量由封闭领域枚举决定，入口拒绝超出该数量的选择。 */
        public ReviewRequest {
            if (selected != null && selected.size() > InvoiceExtractionSuggestion.MAX_PROPOSALS) throw new IllegalArgumentException("Too many invoice selections");
        }
        /** 拒绝客户端传入查验、占用、申请或管理员覆盖状态。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown invoice extraction review field"); }
    }
    /**
     * 外发确认只接受 JSON 布尔值，数字或文本不能隐式形成授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class ConfirmationDeserializer extends JsonDeserializer<Boolean> {
        /** JSON null 交由必填校验处理，其他类型由解析器直接拒绝。 */
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return parser.getBooleanValue();
        }
    }
    /**
     * 运行版本只接受整数，不能把小数截断后匹配并发版本。
     * @author owlzhangfq@gmail.com
     */
    public static final class RunVersionDeserializer extends JsonDeserializer<Long> {
        /** 范围溢出由解析器拒绝，正数要求继续由入口 Bean Validation 校验。 */
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw JsonMappingException.from(parser, "Invoice extraction version must be an integer");
            return parser.getLongValue();
        }
    }
}
