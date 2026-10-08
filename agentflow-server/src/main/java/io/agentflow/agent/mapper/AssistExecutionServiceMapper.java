package io.agentflow.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AssistExecutionService 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AssistExecutionServiceMapper {
    /** 读取 queue 所需的持久化事实。 */
    List<Integer> queue(
            @Param("tenantId") String tenantId, @Param("applicationId") String applicationId);

    /** 读取 lockApplication 所需的持久化事实。 */
    List<String> lockApplication(@Param("tenant") String tenant, @Param("id") String id);
}
