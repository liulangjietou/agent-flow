package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已批准事前额度的关闭入口；参数在此验证，业务写入和成功回执使用同一事务。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ExpenseRequestClosureController {
    private final ExpenseRequestClosureService service;
    private final IdempotencyExecutor idempotency;

    /** 关闭不调用外部财务系统，直接复用现有幂等执行边界。 */
    public ExpenseRequestClosureController(ExpenseRequestClosureService service, IdempotencyExecutor idempotency) {
        this.service = service; this.idempotency = idempotency;
    }

    /** 原申请人显式关闭当前版本，重复原请求只恢复第一次成功回执。 */
    @PostMapping("/api/v1/expense-requests/{id}/close")
    public ResponseEntity<String> close(@PathVariable UUID id, @Valid @RequestBody CloseRequest input, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> service.close(id, input.expectedVersion(), input.comment().trim()));
    }

    /**
     * 关闭必须携带所见额度版本及原因，不能伪造租户、归属或关闭后的状态。
     * @author owlzhangfq@gmail.com
     */
    public record CloseRequest(@NotNull @Positive @JsonDeserialize(using = VersionDeserializer.class) Long expectedVersion,
                               @NotBlank @Size(max = 2000) @JsonDeserialize(using = CommentDeserializer.class) String comment) {
        /** 未声明字段直接失败，避免客户端以为已修改额外的额度数据。 */
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown prior request closure field"); }
    }

    /**
     * 并发版本只接受整数，不能把小数截断后误匹配旧版本。
     * @author owlzhangfq@gmail.com
     */
    public static final class VersionDeserializer extends JsonDeserializer<Long> {
        /** 溢出由解析器拒绝，正数和非空由入口约束检查。 */
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw context.wrongTokenException(parser, Long.class, JsonToken.VALUE_NUMBER_INT, "Expected integer version");
            return parser.getLongValue();
        }
    }

    /**
     * 原因必须为用户明确输入的文本，不能把数字、布尔值等自动转成字符串。
     * @author owlzhangfq@gmail.com
     */
    public static final class CommentDeserializer extends JsonDeserializer<String> {
        /** 文本长度及非空仍由同一入口的约束检查。 */
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw context.wrongTokenException(parser, String.class, JsonToken.VALUE_STRING, "Expected textual closure reason");
            return parser.getText();
        }
    }
}
