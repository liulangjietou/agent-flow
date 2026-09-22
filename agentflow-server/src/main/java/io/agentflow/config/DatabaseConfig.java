package io.agentflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 基础事务和调度配置。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableTransactionManagement
@EnableScheduling
public class DatabaseConfig { }
