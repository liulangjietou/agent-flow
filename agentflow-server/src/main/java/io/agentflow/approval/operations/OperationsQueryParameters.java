package io.agentflow.approval.operations;

import io.agentflow.common.DomainException;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * 运营接口一次校验时间与流程筛选，租户和查询身份不得由客户端覆盖。
 * @author owlzhangfq@gmail.com
 */
public final class OperationsQueryParameters {
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 366;
    private static final int MAX_ORGANIZATION_LENGTH = 128;
    private static final Set<String> ALLOWED = Set.of("from", "to", "processKey", "definitionVersion", "organization");
    private OperationsQueryParameters() { }

    /** 日期均为 UTC 自然日，默认包含今天的近三十天，最长三百六十六天。 */
    public static ApprovalOperationsReadPort.Query parse(Map<String, String> raw, LocalDate today) {
        if (!ALLOWED.containsAll(raw.keySet())) throw invalid();
        try {
            LocalDate to = raw.containsKey("to") ? LocalDate.parse(raw.get("to")) : today;
            LocalDate from = raw.containsKey("from") ? LocalDate.parse(raw.get("from")) : to.minusDays(DEFAULT_DAYS - 1);
            String key = raw.getOrDefault("processKey", "").strip();
            String organization = raw.getOrDefault("organization", "").strip();
            Long version = raw.containsKey("definitionVersion") ? Long.valueOf(raw.get("definitionVersion")) : null;
            if (from.isAfter(to) || to.isAfter(today) || from.getYear() < 1
                    || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS || key.length() > 128
                    || key.chars().anyMatch(Character::isISOControl)
                    || organization.length() > MAX_ORGANIZATION_LENGTH || organization.chars().anyMatch(Character::isISOControl)
                    || version != null && (version < 1 || version > Integer.MAX_VALUE || key.isEmpty())) throw invalid();
            return new ApprovalOperationsReadPort.Query(from, to, key, version, organization);
        } catch (DateTimeException | NumberFormatException exception) { throw invalid(); }
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_OPERATIONS_QUERY", "Invalid UTC date range, process or organization filter");
    }
}
