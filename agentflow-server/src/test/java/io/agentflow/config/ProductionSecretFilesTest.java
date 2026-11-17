package io.agentflow.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产配置把容器密钥目录作为配置树导入：企业配置文件里按租户声明的集成凭据以 ${文件名} 引用密钥文件，
 * 正文不进入 Compose 环境清单、启动参数或配置文件。只加载配置，不启动 Web、数据库或业务 Bean。
 * @author owlzhangfq@gmail.com
 */
class ProductionSecretFilesTest {
    @TempDir Path directory;

    @Test void enterpriseConfigurationResolvesCredentialsFromSecretFiles() throws IOException {
        Path secrets = Files.createDirectory(directory.resolve("secrets"));
        // 与 Compose 文件密钥一致：一行原始值，允许结尾换行。
        Files.writeString(secrets.resolve("notification_smtp_password"), "smtp-value\n");
        Path enterprise = Files.writeString(directory.resolve("enterprise.yml"),
                "agentflow:\n  notifications:\n    smtp-servers:\n      main:\n        password: ${notification_smtp_password}\n");

        try (var context = start("--AGENTFLOW_SECRETS_DIRECTORY=" + secrets + "/",
                "--spring.config.additional-location=file:" + enterprise)) {
            assertThat(context.getEnvironment().getProperty("agentflow.notifications.smtp-servers.main.password"))
                    .isEqualTo("smtp-value");
        }
    }

    @Test void productionStartsWithoutSecretDirectory() {
        try (var context = start("--AGENTFLOW_SECRETS_DIRECTORY=" + directory.resolve("absent") + "/")) {
            assertThat(context.getEnvironment().getProperty("notification_smtp_password")).isNull();
        }
    }

    private static org.springframework.context.ConfigurableApplicationContext start(String... arguments) {
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).profiles("prod")
                .logStartupInfo(false).run(arguments);
    }

    /**
     * 空配置类：只验证配置加载链，不触发自动配置。
     * @author owlzhangfq@gmail.com
     */
    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
