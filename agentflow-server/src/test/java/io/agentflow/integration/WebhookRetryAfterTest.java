package io.agentflow.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应头解析覆盖三种 HTTP 日期、世纪边界以及不可信输入回退。
 * @author owlzhangfq@gmail.com
 */
class WebhookRetryAfterTest {
    private static final Instant NOW = Instant.parse("2026-09-24T05:00:00Z");

    @Test
    void acceptsDelaySecondsWithoutInventingABusinessMaximum() {
        assertThat(WebhookRetryAfter.parse(List.of(" 120 "), NOW)).isEqualTo(NOW.plusSeconds(120));
        assertThat(WebhookRetryAfter.parse(List.of("0"), NOW)).isEqualTo(NOW);
        assertThat(WebhookRetryAfter.parse(List.of("00060"), NOW)).isEqualTo(NOW.plusSeconds(60));
        assertThat(WebhookRetryAfter.parse(List.of("864000"), NOW)).isEqualTo(NOW.plusSeconds(864000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sun, 06 Nov 1994 08:49:37 GMT", "Sunday, 06-Nov-94 08:49:37 GMT", "Sun Nov  6 08:49:37 1994"})
    void acceptsAllThreeHttpDateFormats(String value) {
        assertThat(WebhookRetryAfter.parse(List.of(value), NOW)).isEqualTo(Instant.parse("1994-11-06T08:49:37Z"));
    }

    @Test
    void legacyYearWindowUsesTheFullDateAtFiftyYearsAndCrossesCenturies() {
        assertThat(WebhookRetryAfter.parse(List.of("Thursday, 24-Sep-76 05:00:00 GMT"), NOW))
                .isEqualTo(Instant.parse("2076-09-24T05:00:00Z"));
        assertThat(WebhookRetryAfter.parse(List.of("Thursday, 24-Sep-76 05:00:01 GMT"), NOW))
                .isEqualTo(Instant.parse("1976-09-24T05:00:01Z"));
        assertThat(WebhookRetryAfter.parse(List.of("Sunday, 01-Jan-30 00:00:00 GMT"), Instant.parse("2090-01-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2130-01-01T00:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "+120", "1.5", "120, 240", "tomorrow", "9223372036854775808",
            "9223372036854775807", "999999999999", "Thu, 31 Feb 2026 05:00:00 GMT", "120; secret=value"})
    void ignoresMalformedOrUnrepresentableValues(String value) {
        assertThat(WebhookRetryAfter.parse(List.of(value), NOW)).isNull();
    }

    @Test
    void ignoresMissingRepeatedOrExcessivelyLongHeaders() {
        assertThat(WebhookRetryAfter.parse(List.of(), NOW)).isNull();
        assertThat(WebhookRetryAfter.parse(List.of("120", "240"), NOW)).isNull();
        assertThat(WebhookRetryAfter.parse(List.of("0".repeat(129)), NOW)).isNull();
        assertThat(WebhookRetryAfter.parse(List.of("Fri, 31 Dec 9999 23:59:59 GMT"), NOW))
                .isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
    }
}
