package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.JsonUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 定额补贴必须作为独立的发布规则保留，不能退化成手填数量乘单价上限。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAllowanceDefinitionTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules()
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).setSerializationInclusion(JsonInclude.Include.NON_NULL));

    @Test void existingPublishedJsonRemainsByteIdenticalWithoutChangingItsDefinitionDigest() {
        String stored = "{\"name\":\"普通差旅\",\"rules\":[{\"key\":\"hotel\",\"name\":\"住宿\",\"match\":{\"legalEntityIds\":[],\"categoryCodes\":[\"HOTEL\"],\"cityTiers\":[],\"employeeGrades\":[],\"currency\":\"CNY\"},\"constraints\":{\"effect\":\"ALLOW\",\"unitPriceLimit\":{\"value\":\"200.00\",\"currency\":\"CNY\"},\"limitUnit\":\"NIGHT\",\"allowedServiceLevels\":[],\"priorRequestRequired\":false}}]}";
        var restored = json.read(stored, ExpensePolicyDefinition.class);
        assertThat(restored.rules().get(0).constraints().fixedAllowance()).isNull();
        assertThat(json.write(restored)).isEqualTo(stored);
    }

    @Test void publishedDefinitionRetainsFixedDailyRateAndExplicitDayBasis() {
        var definition = json.read("""
                {"name":"差旅定额","rules":[{"key":"daily","name":"每日补贴",
                "match":{"legalEntityIds":[],"categoryCodes":["ALLOWANCE"],"cityTiers":[],"employeeGrades":[],
                "fromDate":null,"throughDate":null,"currency":"CNY"},
                "constraints":{"effect":"ALLOW","unitPriceLimit":null,"limitUnit":null,"invoiceMaxAgeDays":null,
                "invoiceAgeAction":null,"allowedServiceLevels":[],"priorRequestRequired":false,
                "fixedAllowance":{"dailyRate":{"value":"100.00","currency":"CNY"},"dayCountBasis":"CALENDAR_DAYS_INCLUSIVE"}}}]}
                """, ExpensePolicyDefinition.class);
        definition.requirePublishable(new ExpenseCategoryCatalog("demo", 1,
                List.of(new ExpenseCategoryCatalog.Category("ALLOWANCE", "差旅补贴", List.of(ExpenseLine.Unit.DAY), true))));
        assertThat(json.write(definition)).contains("\"fixedAllowance\"", "CALENDAR_DAYS_INCLUSIVE");
    }
}
