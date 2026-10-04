package io.agentflow.expense.reporting;

import io.agentflow.common.DomainException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;

/**
 * 自然日边界与原标识严格匹配，查询不能误改合法历史类别的空格。
 * @author owlzhangfq@gmail.com
 */
class ExpenseReportQueryParametersTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    @Test void includesBothDaysAndAcceptsExactly366Days() {
        var raw = new LinkedMultiValueMap<String, String>(); raw.add("from", TODAY.minusDays(365).toString());
        var query = ExpenseReportQueryParameters.parse(raw, TODAY);
        assertThat(query.from()).isEqualTo(TODAY.minusDays(365)); assertThat(query.to()).isEqualTo(TODAY);
        assertThat(java.time.Duration.between(query.start(), query.end()).toDays()).isEqualTo(366);
        assertThat(ExpenseReportQueryParameters.parse(new LinkedMultiValueMap<>(), TODAY).from()).isEqualTo(TODAY.minusDays(29));
        raw.set("from", TODAY.minusDays(366).toString());
        assertThatThrownBy(() -> ExpenseReportQueryParameters.parse(raw, TODAY)).isInstanceOf(DomainException.class);
    }
    @Test void matchesCategoryExactlyAsTheOriginalCatalogStoredIt() {
        var raw = new LinkedMultiValueMap<String, String>(); raw.add("categoryCode", " ORIGINAL CODE ");
        assertThat(ExpenseReportQueryParameters.parse(raw, TODAY).categoryCode()).isEqualTo(" ORIGINAL CODE ");
        raw.put("categoryCode", List.of("A", "B"));
        assertThatThrownBy(() -> ExpenseReportQueryParameters.parse(raw, TODAY)).isInstanceOf(DomainException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"0000-01-01", "2026-02-30", "2026-10-05", "2026-1-1", "", "tomorrow"})
    void rejectsInvalidOrFutureDates(String value) {
        var raw = new LinkedMultiValueMap<String, String>(); raw.add("to", value);
        assertThatThrownBy(() -> ExpenseReportQueryParameters.parse(raw, TODAY)).isInstanceOf(DomainException.class);
    }
}
