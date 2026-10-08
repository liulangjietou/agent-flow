package io.agentflow.servicetask.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ServiceTaskCatalog 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ServiceTaskCatalogMapper {
    /** 读取 install 所需的持久化事实。 */
    List<Integer> install();

    /** 新增 install 所需的持久化事实。 */
    int install2(
            @Param("tenantId") String tenantId,
            @Param("operationKey") String operationKey,
            @Param("operationVersion") Long operationVersion,
            @Param("contractDigest") String contractDigest,
            @Param("targetDigest") String targetDigest,
            @Param("contractJson") String contractJson,
            @Param("installedAt") Timestamp installedAt);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("key") String key,
            @Param("version") Long version);

    /** 读取 list 所需的持久化事实。 */
    List<SqlRow> list(
            @Param("tenant") String tenant,
            @Param("afterKey") String afterKey,
            @Param("limit") Integer limit);

    /** 按 versions 的筛选条件执行数据库查询。 */
    List<Long> versionsQuery(
            @Param("hasBeforeVersion") boolean hasBeforeVersion,
            @Param("parameters") Object[] parameters);
}
