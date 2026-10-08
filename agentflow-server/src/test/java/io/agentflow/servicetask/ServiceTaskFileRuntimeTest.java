package io.agentflow.servicetask;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.agentflow.support.H2FileDatabases.fileUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整迁移及流程引擎启动后，文件库必须保留已验证的持久性配置。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "agentflow.service-tasks.worker-enabled=false",
        "agentflow.timers.enabled=false"})
class ServiceTaskFileRuntimeTest {
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void fileDatabase(DynamicPropertyRegistry properties) throws Exception {
        Path directory = Files.createTempDirectory("agentflow-service-file-runtime-");
        properties.add("spring.datasource.url", () -> fileUrl(directory.resolve("runtime")));
    }

    @Test
    void completeApplicationStartupRetainsFileDurabilitySettings() {
        // H2 分别展示持久设置和当前运行设置，二者都必须为零。
        assertThat(jdbc.queryForList("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='WRITE_DELAY'", String.class))
                .isNotEmpty().containsOnly("0");
        assertThat(jdbc.queryForList("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='REUSE_SPACE'", Boolean.class))
                .containsExactly(false);
    }
}
