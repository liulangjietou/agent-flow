package io.agentflow.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * DraftAssistService 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DraftAssistServiceMapper {
    /** 读取 claim 所需的持久化事实。 */
    List<String> claim(
            @Param("tenant") String tenant, @Param("applicationId") String applicationId);
}
