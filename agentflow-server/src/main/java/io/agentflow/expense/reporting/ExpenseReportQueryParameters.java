package io.agentflow.expense.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.DomainException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务报表入口一次校验自然日和原组织筛选，客户端不能覆盖租户或身份。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseReportQueryParameters {
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 366;
    private static final int MAX_CATEGORY_LENGTH = 64;
    private static final Set<String> ALLOWED = Set.of("from", "to", "legalEntityId", "departmentId", "categoryCode");
    private ExpenseReportQueryParameters() { }

    /** 默认包含今天，日期两端均包含；重复参数和非规范标识必须在入口拒绝。 */
    public static Query parse(MultiValueMap<String, String> raw, LocalDate today) {
        if (!ALLOWED.containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        try {
            LocalDate to = raw.containsKey("to") ? LocalDate.parse(raw.getFirst("to")) : today;
            LocalDate from = raw.containsKey("from") ? LocalDate.parse(raw.getFirst("from")) : to.minusDays(DEFAULT_DAYS - 1);
            String category = raw.getFirst("categoryCode");
            if (from.getYear() < 1 || from.isAfter(to) || to.isAfter(today) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS
                    || category != null && (StringUtils.isBlank(category) || category.length() > MAX_CATEGORY_LENGTH)) throw invalid();
            return new Query(from, to, identifier(raw.getFirst("legalEntityId")), identifier(raw.getFirst("departmentId")), category);
        } catch (DateTimeException | IllegalArgumentException exception) { throw invalid(); }
    }
    private static UUID identifier(String value) {
        if (value == null) return null;
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) throw invalid();
        return parsed;
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_REPORT_QUERY", "Invalid UTC date range or original organization/category filter"); }

    /**
     * 已校验查询在整个只读事务内固定，所有来源复用同一日期边界。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Query(LocalDate from, LocalDate to, UUID legalEntityId, UUID departmentId, String categoryCode) {
        /** 提交和尝试范围使用左闭右开的 UTC 时间边界。 */
        public Instant start() { return from.atStartOfDay().toInstant(ZoneOffset.UTC); }
        /** 自然日结束转为次日零点，避免精度差异遗漏最后一秒的事实。 */
        public Instant end() { return to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC); }
    }
}
