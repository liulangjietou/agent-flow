package io.agentflow.integration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

/**
 * 将 HTTP Retry-After 转为等待期限；不让不可信响应头进入日志、领域解析或数据库。
 * @author owlzhangfq@gmail.com
 */
final class WebhookRetryAfter {
    private static final int MAX_HEADER_LENGTH = 128;
    // 四位年份上界保证 HTTP 日期、JDBC 和管理页面可共同表示，不是业务等待时长限制。
    private static final Instant MAX_DEADLINE = Instant.parse("9999-12-31T23:59:59Z");
    private static final int FUTURE_YEAR_WINDOW = 50;
    private static final int CENTURY_YEARS = 100;
    private static final DateTimeFormatter IMF_FIXDATE = formatter("EEE, dd MMM uuuu HH:mm:ss 'GMT'");
    private static final DateTimeFormatter ASCTIME = formatter("EEE MMM d HH:mm:ss uuuu");

    private WebhookRetryAfter() { }

    /** 缺失、重复、格式错误或超出可表示范围时，返回空期限，交由领域使用固定退避。 */
    static Instant parse(List<String> values, Instant receivedAt) {
        if (values.size() != 1 || values.get(0).length() > MAX_HEADER_LENGTH) return null;
        String value = values.get(0).trim();
        try {
            if (value.matches("[0-9]+")) return representable(receivedAt.plusSeconds(Long.parseLong(value)));
            return parseDate(value, receivedAt);
        } catch (DateTimeException | ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    private static Instant parseDate(String value, Instant receivedAt) {
        var now = LocalDateTime.ofInstant(receivedAt, ZoneOffset.UTC);
        // RFC 850 的两位年份先取到未来第 50 年；再按完整日期判断是否必须回退一个世纪。
        var legacy = new DateTimeFormatterBuilder().appendPattern("EEEE, dd-MMM-")
                .appendValueReduced(ChronoField.YEAR, 2, 2, now.getYear() - FUTURE_YEAR_WINDOW + 1)
                .appendPattern(" HH:mm:ss 'GMT'").toFormatter(Locale.US).withResolverStyle(ResolverStyle.STRICT)
                .withResolverFields(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR, ChronoField.DAY_OF_MONTH,
                        ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE);
        for (var format : List.of(IMF_FIXDATE, ASCTIME, legacy)) {
            try {
                // asctime 用双空格补齐一位日期，空格归一化后按同一格式解析。
                var date = LocalDateTime.parse(format == ASCTIME ? value.replaceAll(" +", " ") : value, format);
                if (format == legacy && date.isAfter(now.plusYears(FUTURE_YEAR_WINDOW))) {
                    date = date.minusYears(CENTURY_YEARS);
                }
                return representable(date.toInstant(ZoneOffset.UTC));
            } catch (DateTimeException invalid) {
                // 按协议尝试三种日期格式；不记录可能带有敏感内容的原始值或解析异常。
            }
        }
        return null;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT);
    }

    private static Instant representable(Instant deadline) {
        return deadline.isAfter(MAX_DEADLINE) ? null : deadline;
    }
}
