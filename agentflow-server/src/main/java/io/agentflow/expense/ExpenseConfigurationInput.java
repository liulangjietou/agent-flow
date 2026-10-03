package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * HTTP 配置边界拒绝未知字段和类型转换，业务规则转为纯领域值对象后不再重复入口校验。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseConfigurationInput {
    private ExpenseConfigurationInput() { }

    /**
     * 类别全量修订必须声明旧版本和理由。
     * @author owlzhangfq@gmail.com
     */
    public record Categories(@NotNull @Min(0) @JsonDeserialize(using = Revision.class) Long expectedVersion,
            @NotNull @Size(max = ExpenseCategoryCatalog.MAX_CATEGORIES) List<@NotNull @Valid Category> categories,
            @NotBlank @Size(max = 2000) @JsonDeserialize(using = Text.class) String comment) {
        /** 未声明字段不能被误认为已经生效。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 类别代码和启停状态均由管理员明确给出。
     * @author owlzhangfq@gmail.com
     */
    public record Category(@NotBlank @JsonDeserialize(using = Text.class) String code,
            @NotBlank @JsonDeserialize(using = Text.class) String name,
            @NotNull @JsonDeserialize(contentUsing = Text.class) List<@NotNull String> units,
            @NotNull @JsonDeserialize(using = Flag.class) Boolean active) {
        /** 类型和值校验通过后进入类别领域模型。 */
        public ExpenseCategoryCatalog.Category domain() { return new ExpenseCategoryCatalog.Category(code, name, units.stream().map(value -> enumeration(value, ExpenseLine.Unit.class)).toList(), active); }
        /** 类别没有删除或归属改写字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 草稿保存不接受客户端指定身份或发布游标。
     * @author owlzhangfq@gmail.com
     */
    public record Draft(@NotNull @Min(0) @JsonDeserialize(using = Revision.class) Long expectedRevision,
            @NotNull @Valid Definition definition, @NotBlank @Size(max = 2000) @JsonDeserialize(using = Text.class) String comment) {
        /** 发布状态只有独立发布入口能改变。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 三个版本分别保护草稿、类别目录和租户当前制度。
     * @author owlzhangfq@gmail.com
     */
    public record Publish(@NotNull @Positive @JsonDeserialize(using = Revision.class) Long expectedDraftRevision,
            @NotNull @Positive @JsonDeserialize(using = Revision.class) Long expectedCategoryRevision,
            @NotNull @Min(0) @JsonDeserialize(using = Revision.class) Long expectedActiveRevision,
            @NotBlank @Size(max = 2000) @JsonDeserialize(using = Text.class) String comment) {
        /** 客户端不能伪造新版本号或发布时间。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 有序规则组成一个完整制度集，空规则仅允许保存为草稿。
     * @author owlzhangfq@gmail.com
     */
    public record Definition(@NotBlank @JsonDeserialize(using = Text.class) String name,
            @NotNull @Size(max = ExpensePolicyDefinition.MAX_RULES) List<@NotNull @Valid Rule> rules) {
        /** 按页面显式顺序生成规则，不能按名称偷偷重排。 */
        public ExpensePolicyDefinition domain() { return new ExpensePolicyDefinition(name, rules.stream().map(Rule::domain).toList()); }
        /** 费用制度不接受脚本或客户端税率。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 稳定规则键关联匹配条件及多个同时生效的约束。
     * @author owlzhangfq@gmail.com
     */
    public record Rule(@NotBlank @JsonDeserialize(using = Text.class) String key,
            @NotBlank @JsonDeserialize(using = Text.class) String name, @NotNull @Valid Match match, @NotNull @Valid Constraints constraints) {
        /** 领域模型负责条件和约束的交叉一致性。 */
        public ExpensePolicyDefinition.Rule domain() { return new ExpensePolicyDefinition.Rule(key, name, match.domain(), constraints.domain()); }
        /** 规则无任意执行入口。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 维度集合必须显式存在，空集合表示任意值，职级与城市等级由企业事实源解析。
     * @author owlzhangfq@gmail.com
     */
    public record Match(@NotNull List<@NotNull UUID> legalEntityIds,
            @NotNull @JsonDeserialize(contentUsing = Text.class) List<@NotNull String> categoryCodes,
            @NotNull @JsonDeserialize(contentUsing = Text.class) List<@NotNull String> cityTiers,
            @NotNull @JsonDeserialize(contentUsing = Text.class) List<@NotNull String> employeeGrades,
            @JsonDeserialize(using = Text.class) String fromDate, @JsonDeserialize(using = Text.class) String throughDate,
            @JsonDeserialize(using = Text.class) String currency) {
        /** 日期间隔和币种交给领域模型核对，日期文本只接受标准格式。 */
        public ExpensePolicyDefinition.Match domain() { return new ExpensePolicyDefinition.Match(legalEntityIds, categoryCodes, cityTiers, employeeGrades, date(fromDate), date(throughDate), currency); }
        /** 不接受员工自行声明的实际职级或城市级别。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }
    /**
     * 未配置的限制保持空值，禁止把缺失的布尔值当成财务规则默认值。
     * @author owlzhangfq@gmail.com
     */
    public record Constraints(@NotBlank @JsonDeserialize(using = Text.class) String effect, Money unitPriceLimit,
            @JsonDeserialize(using = Text.class) String limitUnit, @JsonDeserialize(using = Days.class) Integer invoiceMaxAgeDays,
            @JsonDeserialize(using = Text.class) String invoiceAgeAction,
            @NotNull @JsonDeserialize(contentUsing = Text.class) List<@NotNull String> allowedServiceLevels,
            @NotNull @JsonDeserialize(using = Flag.class) Boolean priorRequestRequired) {
        /** 限额金额使用系统既有精确十进制协议。 */
        public ExpensePolicyDefinition.Constraints domain() { return new ExpensePolicyDefinition.Constraints(enumeration(effect, ExpensePolicyDefinition.Effect.class), unitPriceLimit,
                enumeration(limitUnit, ExpenseLine.Unit.class), invoiceMaxAgeDays, enumeration(invoiceAgeAction, ExpensePolicyDefinition.AgeAction.class), allowedServiceLevels, priorRequestRequired); }
        /** 未声明的约束必须失败，不能让管理员误以为已发布。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw unknown(); }
    }

    private static IllegalArgumentException unknown() { return new IllegalArgumentException("Unknown expense configuration field"); }
    private static DomainException malformed() { return new DomainException("INVALID_EXPENSE_CONFIGURATION_REQUEST", "Expense configuration contains an invalid date or enum value"); }
    private static <E extends Enum<E>> E enumeration(String value, Class<E> type) {
        if (value == null) return null;
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException invalid) { throw malformed(); }
    }
    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw malformed();
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException invalid) { throw malformed(); }
    }

    /**
     * 并发版本禁止截断小数、把字符串或布尔值转成整数。
     * @author owlzhangfq@gmail.com
     */
    public static final class Revision extends JsonDeserializer<Long> {
        /** 非负或正数约束由相应入口字段声明。 */
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw context.wrongTokenException(parser, Long.class, JsonToken.VALUE_NUMBER_INT, "Expected integer revision");
            return parser.getLongValue();
        }
    }
    /**
     * 文本不接受数字或布尔值的隐式转换。
     * @author owlzhangfq@gmail.com
     */
    public static final class Text extends JsonDeserializer<String> {
        /** 空值和长度仍由入口及领域规则决定。 */
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw context.wrongTokenException(parser, String.class, JsonToken.VALUE_STRING, "Expected configuration text");
            return parser.getText();
        }
    }
    /**
     * 启停及事前申请要求必须为真实 JSON 布尔值。
     * @author owlzhangfq@gmail.com
     */
    public static final class Flag extends JsonDeserializer<Boolean> {
        /** 不接受数字和带引号的布尔值。 */
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE)) throw context.wrongTokenException(parser, Boolean.class, JsonToken.VALUE_TRUE, "Expected boolean configuration flag");
            return parser.getBooleanValue();
        }
    }
    /**
     * 票据时限的天数必须为整数。
     * @author owlzhangfq@gmail.com
     */
    public static final class Days extends JsonDeserializer<Integer> {
        /** 天数范围由费用制度模型统一验证。 */
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw context.wrongTokenException(parser, Integer.class, JsonToken.VALUE_NUMBER_INT, "Expected integer invoice age");
            return parser.getIntValue();
        }
    }
}
