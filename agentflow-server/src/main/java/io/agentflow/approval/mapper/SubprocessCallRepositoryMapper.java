package io.agentflow.approval.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JdbcSubprocessCallRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SubprocessCallRepositoryMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("parentApplicationId") String parentApplicationId,
            @Param("parentRoundNo") Integer parentRoundNo,
            @Param("parentInstanceId") String parentInstanceId,
            @Param("parentRuntimeDefinitionId") String parentRuntimeDefinitionId,
            @Param("nodeId") String nodeId,
            @Param("nodeName") String nodeName,
            @Param("activationId") String activationId,
            @Param("childApplicationId") String childApplicationId,
            @Param("childRoundNo") Object childRoundNo,
            @Param("childInstanceId") String childInstanceId,
            @Param("childDefinitionId") String childDefinitionId,
            @Param("childProcessKey") String childProcessKey,
            @Param("childDefinitionVersion") Long childDefinitionVersion,
            @Param("childRuntimeDefinitionId") String childRuntimeDefinitionId,
            @Param("policyJson") String policyJson,
            @Param("createdAt") OffsetDateTime createdAt);

    /** 读取 findByChild 所需的持久化事实。 */
    List<SqlRow> findByChild(
            @Param("tenantId") String tenantId,
            @Param("childApplicationId") String childApplicationId);

    /** 读取 findByActivation 所需的持久化事实。 */
    List<SqlRow> findByActivation(
            @Param("tenantId") String tenantId,
            @Param("parentInstanceId") String parentInstanceId,
            @Param("activationId") String activationId);

    /** 读取 findByParentRound 所需的持久化事实。 */
    List<SqlRow> findByParentRound(
            @Param("tenantId") String tenantId,
            @Param("parentApplicationId") String parentApplicationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 pageByParentRound 所需的持久化事实。 */
    List<SqlRow> pageByParentRound(
            @Param("tenantId") String tenantId,
            @Param("parentApplicationId") String parentApplicationId,
            @Param("roundNo") Integer roundNo,
            @Param("afterId") String afterId);

    /** 执行 pageByParentRound 的条件查询。 */
    List<SqlRow> pageByParentRoundQuery(@Param("parameters") Object[] parameters);

    /** 执行 pageByParentRound 的条件查询。 */
    List<SqlRow> pageByParentRoundQuery2(@Param("parameters") Object[] parameters);
}
