package io.agentflow.approval.operations;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * 日期边界使用固定 UTC 日期，验证闰年和含首尾的最大跨度。
 * @author owlzhangfq@gmail.com
 */
class OperationsQueryParametersTest {
    @Test
    void defaultsToThirtyDaysEndingAtRequestedUtcDate() {
        var query = OperationsQueryParameters.parse(Map.of(), LocalDate.parse("2024-03-01"));
        assertThat(query.from()).isEqualTo("2024-02-01");
        assertThat(query.to()).isEqualTo("2024-03-01");
        var historic = OperationsQueryParameters.parse(Map.of("to", "2020-03-01"), LocalDate.parse("2024-03-01"));
        assertThat(historic.from()).isEqualTo("2020-02-01");
        assertThat(historic.toExclusive()).hasToString("2020-03-02T00:00:00Z");
    }

    @Test
    void accepts366InclusiveDaysAndRejectsOneMoreOrUntrustedInput() {
        LocalDate today = LocalDate.parse("2024-03-01");
        assertThat(OperationsQueryParameters.parse(Map.of("from", "2023-03-02", "to", "2024-03-01"), today).from())
                .isEqualTo("2023-03-02");
        for (var raw : java.util.List.of(Map.of("from", "2023-03-01"), Map.of("from", "2023-02-29"),
                Map.of("from", "0000-03-01"), Map.of("processKey", "x\ny"), Map.of("processKey", "x".repeat(129)),
                Map.of("processKey", "x", "definitionVersion", "2147483648"))) {
            assertThatThrownBy(() -> OperationsQueryParameters.parse(raw, today)).isInstanceOf(DomainException.class)
                    .hasMessage("Invalid UTC date range or process filter");
        }
    }
}
