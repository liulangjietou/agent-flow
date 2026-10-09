package io.agentflow.approval.process.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * FlowableExecutionOriginListener 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FlowableExecutionOriginListenerMapper {
    /** 新增 capture 所需的持久化事实。 */
    int capture(
            @Param("tenant") String tenant,
            @Param("kind") String kind,
            @Param("id") String id,
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("objectKind") String objectKind,
            @Param("objectId") String objectId);
}
