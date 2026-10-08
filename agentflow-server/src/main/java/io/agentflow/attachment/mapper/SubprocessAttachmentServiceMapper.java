package io.agentflow.attachment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SubprocessAttachmentService 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SubprocessAttachmentServiceMapper {
    /** 新增 persist 所需的持久化事实。 */
    int persist(
            @Param("tenantId") String tenantId,
            @Param("callId") String callId,
            @Param("parentApplicationId") String parentApplicationId,
            @Param("childApplicationId") String childApplicationId,
            @Param("sourceFieldPath") String sourceFieldPath,
            @Param("sourceAttachmentId") String sourceAttachmentId,
            @Param("targetFieldPath") String targetFieldPath,
            @Param("targetAttachmentId") String targetAttachmentId);
}
