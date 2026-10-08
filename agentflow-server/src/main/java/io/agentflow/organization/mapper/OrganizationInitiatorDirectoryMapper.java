package io.agentflow.organization.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * OrganizationInitiatorDirectory 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface OrganizationInitiatorDirectoryMapper {
    /** 读取 options 所需的持久化事实。 */
    List<SqlRow> options(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 读取 findCurrent 所需的持久化事实。 */
    List<SqlRow> findCurrent(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("appointmentId") String appointmentId);
}
