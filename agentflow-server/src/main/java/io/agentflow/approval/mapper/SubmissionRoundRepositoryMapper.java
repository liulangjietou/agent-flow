package io.agentflow.approval.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JdbcSubmissionRoundRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SubmissionRoundRepositoryMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("processInstanceId") String processInstanceId,
            @Param("definitionVersion") Long definitionVersion,
            @Param("title") String title,
            @Param("payloadJson") String payloadJson,
            @Param("submittedBy") String submittedBy,
            @Param("submittedAt") OffsetDateTime submittedAt,
            @Param("formSchemaJson") String formSchemaJson,
            @Param("initiatorContextJson") String initiatorContextJson,
            @Param("initiatorLegalEntityName") String initiatorLegalEntityName,
            @Param("initiatorDepartmentName") String initiatorDepartmentName,
            @Param("initiatorPositionName") String initiatorPositionName,
            @Param("riskLevel") String riskLevel,
            @Param("riskJson") String riskJson);

    /** 读取 findAll 所需的持久化事实。 */
    List<SqlRow> findAll(
            @Param("tenantId") String tenantId, @Param("applicationId") String applicationId);

    /** 读取 findByRound 所需的持久化事实。 */
    List<SqlRow> findByRound(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 更新 complete 所需的持久化事实。 */
    int complete(
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("completedBy") String completedBy,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("processInstanceId") String processInstanceId);

    /** 读取 complete 所需的持久化事实。 */
    List<Boolean> complete2(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);
}
