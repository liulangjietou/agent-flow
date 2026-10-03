package io.agentflow.expense;

import io.agentflow.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 费用配置必须由实际认证入口管理，空配置不能伪装成已发布的企业标准。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "spring.datasource.url=jdbc:h2:mem:expense-configuration;DB_CLOSE_DELAY=-1",
        "agentflow.advances.overdue.reminders-enabled=false", "agentflow.timers.enabled=false",
        "agentflow.attachments.directory=/fyoung/tmp/agentflow-expense-configuration-test-files"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpenseConfigurationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    @Test void initialCategoryRegistryIsExplicitlyUnconfigured() throws Exception {
        mvc.perform(get("/api/v1/admin/expense-categories").header("Authorization", "Bearer " + auth.login("demo", "admin", "demo").token()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.categories").isEmpty());
    }
}
