package io.agentflow.attachment.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcAttachmentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AttachmentRepositoryMapper {
    /** 读取 lockApplication 所需的持久化事实。 */
    List<SqlRow> lockApplication(
            @Param("tenant") String tenant, @Param("application") String application);

    /** 读取 requireCapacity 所需的持久化事实。 */
    List<SqlRow> requireCapacity(
            @Param("tenant") String tenant, @Param("application") String application);

    /** 新增 insert 所需的持久化事实。 */
    int insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("fieldPath") String fieldPath,
            @Param("filename") String filename,
            @Param("byteSize") Long byteSize,
            @Param("sha256") String sha256,
            @Param("createdBy") String createdBy,
            @Param("createdAt") Object createdAt,
            @Param("status") String status,
            @Param("contentId") String contentId);

    /** 读取 get 所需的持久化事实。 */
    List<SqlRow> get(
            @Param("tenant") String tenant,
            @Param("application") String application,
            @Param("id") String id);

    /** 更新 transition 所需的持久化事实。 */
    int transition(
            @Param("status") String status,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("id") String id);

    /** 新增 freeze 所需的持久化事实。 */
    int freeze(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("fieldPath") String fieldPath,
            @Param("attachmentId") String attachmentId);

    /** 读取 frozen 所需的持久化事实。 */
    List<Integer> frozen(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("round") Integer round,
            @Param("fieldPath") String fieldPath,
            @Param("id") String id);
}
