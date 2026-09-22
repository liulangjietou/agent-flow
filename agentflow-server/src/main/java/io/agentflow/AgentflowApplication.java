package io.agentflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** AgentFlow 审批平台启动入口。 */
@SpringBootApplication
public class AgentflowApplication {
    /** 启动 Spring Boot 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(AgentflowApplication.class, args);
    }
}
