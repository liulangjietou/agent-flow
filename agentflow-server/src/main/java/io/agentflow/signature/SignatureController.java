package io.agentflow.signature;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 用户电子签入口沿用当前身份和幂等事务，所有读取和成功重放都禁止浏览器缓存。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1")
public class SignatureController {
    private final SignatureOperationService operations;
    private final SignaturePublicService views;
    private final IdempotencyExecutor idempotency;
    /** 明确区分业务写入与只读投影，HTTP 调用始终由后台执行。 */
    public SignatureController(SignatureOperationService operations, SignaturePublicService views, IdempotencyExecutor idempotency) {
        this.operations = operations; this.views = views; this.idempotency = idempotency;
    }
    /** 仅返回当前具名主体可选择的已启用资料。 */
    @GetMapping("/signatures/options")
    public ResponseEntity<SignaturePublicService.Options> options() { return noStore(views.options()); }
    /** 明确轮次分页，重复或未知查询参数不被静默忽略。 */
    @GetMapping("/applications/{applicationId}/signatures")
    public ResponseEntity<SignaturePublicService.Page> list(@PathVariable UUID applicationId, @RequestParam MultiValueMap<String, String> query) {
        if (!Set.of("roundNo", "afterId", "limit").containsAll(query.keySet()) || query.values().stream().anyMatch(values -> values.size() != 1)) throw invalidQuery();
        try {
            int round = positiveInteger(query.getFirst("roundNo"), Integer.MAX_VALUE);
            int limit = query.containsKey("limit") ? positiveInteger(query.getFirst("limit"), 100) : 25;
            String cursor = query.getFirst("afterId"); UUID after = cursor == null ? null : UUID.fromString(cursor);
            if (after != null && !after.toString().equals(cursor)) throw invalidQuery();
            return noStore(views.list(applicationId, round, after, limit));
        } catch (IllegalArgumentException invalid) { throw invalidQuery(); }
    }
    /** 缓存命中前检查当前选中文件权限，新授权的来源版本仅在首次写入时检查。 */
    @PostMapping("/applications/{applicationId}/signatures")
    public ResponseEntity<String> create(@PathVariable UUID applicationId, @RequestBody CreateInput body, HttpServletRequest request) {
        var input = body.command(); var originals = views.preflight(applicationId, input);
        return uncached(idempotency.executePrepared(request, HttpStatus.CREATED,
                () -> { views.requireOriginalsAvailable(originals); return input; },
                prepared -> SignaturePublicService.Receipt.of(operations.create(applicationId, prepared, request, Instant.now()))));
    }
    /** 查询不领取、不续租，也不触发网络操作。 */
    @GetMapping("/applications/{applicationId}/signatures/{id}")
    public ResponseEntity<SignaturePublicService.View> detail(@PathVariable UUID applicationId, @PathVariable UUID id) { return noStore(views.detail(applicationId, id)); }
    /** 原发起人取消未发送授权，成功重放仍复核当前字段权限。 */
    @PostMapping("/applications/{applicationId}/signatures/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID applicationId, @PathVariable UUID id, @RequestBody CancelInput body, HttpServletRequest request) {
        long version = version(body.expectedVersion()); operations.get(applicationId, id);
        return uncached(idempotency.execute(request, HttpStatus.OK,
                () -> SignaturePublicService.Receipt.of(operations.cancel(applicationId, id, version, Instant.now()))));
    }
    /** 强制下载并禁止 MIME 嗅探，结果不可作为站内 HTML 或脚本执行。 */
    @GetMapping("/applications/{applicationId}/signatures/{id}/documents/{documentId}/content")
    public ResponseEntity<byte[]> download(@PathVariable UUID applicationId, @PathVariable UUID id, @PathVariable UUID documentId) {
        var file = views.download(applicationId, id, documentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString()).body(file.content());
    }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private static ResponseEntity<String> uncached(ResponseEntity<String> value) { return ResponseEntity.status(value.getStatusCode()).headers(value.getHeaders()).cacheControl(CacheControl.noStore()).body(value.getBody()); }
    private static long version(String value) {
        try { if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalidInput(); return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw invalidInput(); }
    }
    private static int positiveInteger(String value, int maximum) {
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) throw invalidQuery();
        int number = Integer.parseInt(value); if (number > maximum) throw invalidQuery(); return number;
    }
    private static DomainException invalidQuery() { return new DomainException("INVALID_SIGNATURE_QUERY", "Signature query is invalid"); }
    private static DomainException invalidInput() { return new DomainException("INVALID_SIGNATURE_REQUEST", "Signature version or input is invalid"); }
    /**
     * 版本采用十进制文本，避免浏览器截断长整数；实际签署人由服务器资料决定。
     * @author owlzhangfq@gmail.com
     */
    public record CreateInput(@JsonDeserialize(using = RoundValue.class) int roundNo,
                              @JsonDeserialize(using = TextValue.class) String expectedVersion,
                              @JsonDeserialize(using = TextValue.class) String profileKey,
                              @JsonDeserialize(using = TextValue.class) String profileVersion,
                              List<UUID> documentIds, @JsonDeserialize(using = TextValue.class) String purpose, Instant validUntil) {
        SignatureAccess.CreateInput command() { return new SignatureAccess.CreateInput(roundNo, version(expectedVersion), profileKey, version(profileVersion), documentIds, purpose, validUntil); }
        /** 不允许夹带租户、操作者、服务方账户或目标地址。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw invalidInput(); }
        @Override public String toString() { return "SignatureCreateInput[redacted]"; }
    }
    /**
     * 取消不接收替代授权、远端撤销命令或新的签署参数。
     * @author owlzhangfq@gmail.com
     */
    public record CancelInput(@JsonDeserialize(using = TextValue.class) String expectedVersion) {
        /** 明确拒绝未定义的控制字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw invalidInput(); }
    }
    /**
     * 文本版本、资料和授权说明必须来自 JSON 字符串，不能隐式转换数字或布尔值。
     * @author owlzhangfq@gmail.com
     */
    public static final class TextValue extends JsonDeserializer<String> {
        /** 格式和业务范围继续由入口命令统一校验。 */
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw context.wrongTokenException(parser, String.class, JsonToken.VALUE_STRING, "Expected signature text");
            return parser.getText();
        }
    }
    /**
     * 轮次禁止截断小数或把文本轮次转换为整数，以免选择错误的审批来源。
     * @author owlzhangfq@gmail.com
     */
    public static final class RoundValue extends JsonDeserializer<Integer> {
        /** 整数溢出由解析器拒绝，正数约束由入口命令校验。 */
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw context.wrongTokenException(parser, Integer.class, JsonToken.VALUE_NUMBER_INT, "Expected integer signature round");
            return parser.getIntValue();
        }
    }
}
