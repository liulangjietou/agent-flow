package io.agentflow.organization.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcApprovalProxyRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApprovalProxyRepositoryMapper {
    /** 新增 insert 所需的持久化事实。 */
    int insert(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("definitionId") String definitionId,
            @Param("principalId") String principalId,
            @Param("substituteId") String substituteId,
            @Param("startsAt") Timestamp startsAt,
            @Param("endsAt") Timestamp endsAt,
            @Param("reason") String reason,
            @Param("createdBy") String createdBy,
            @Param("createdAt") Timestamp createdAt,
            @Param("traceId") String traceId);

    /** 更新 revoke 所需的持久化事实。 */
    int revoke(
            @Param("actor") String actor,
            @Param("reason") String reason,
            @Param("atAt") Timestamp atAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") Long expectedRevision);

    /** 读取 overlaps 所需的持久化事实。 */
    List<Boolean> overlaps(
            @Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId,
            @Param("principalId") String principalId,
            @Param("startsAt") Timestamp startsAt,
            @Param("endsAt") Timestamp endsAt);

    /** 读取 list 所需的持久化事实。 */
    List<SqlRow> list(
            @Param("tenantId") String tenantId,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 读取 list 所需的持久化事实。 */
    List<SqlRow> list2(
            @Param("tenantId") String tenantId,
            @Param("personId") String personId,
            @Param("substituteId") String substituteId,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 读取 activeForSubstitute 所需的持久化事实。 */
    List<SqlRow> activeForSubstitute(
            @Param("tenantId") String tenantId,
            @Param("subject") String subject,
            @Param("observedAt") Timestamp observedAt,
            @Param("startsAt") Timestamp startsAt,
            @Param("endsAt") Timestamp endsAt);

    /** 读取 findForAccess 所需的数据库事实。 */
    List<SqlRow> findForAccess(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("lock") boolean lock);
}
