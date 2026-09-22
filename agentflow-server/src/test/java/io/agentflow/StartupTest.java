package io.agentflow;

import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 覆盖 Flyway 和 Flowable 在同一数据源上的初始化。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:startup;DB_CLOSE_DELAY=-1",
        "flowable.async-executor-activate=false"
})
class StartupTest {
    @Autowired
    private RepositoryService repositoryService;

    @Test
    void deploysBundledProcess() {
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("expense-reimbursement").count()).isEqualTo(1);
    }
}
