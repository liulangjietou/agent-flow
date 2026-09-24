package io.agentflow;

import io.agentflow.database.DatabaseSchemaCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;

/**
 * AgentFlow 审批平台启动入口。
 * @author owlzhangfq@gmail.com
 */
// 会话持久化由显式企业配置启用，不能仅因引入依赖改变演示认证行为。
@SpringBootApplication(exclude = SessionAutoConfiguration.class)
public class AgentflowApplication {
    /** 启动 Spring Boot 应用。 */
    public static void main(String[] args) {
        if (DatabaseSchemaCommand.requested(args)) {
            System.exit(DatabaseSchemaCommand.run(args, System.getenv(), System.out, System.err));
            return;
        }
        SpringApplication.run(AgentflowApplication.class, args);
    }
}
