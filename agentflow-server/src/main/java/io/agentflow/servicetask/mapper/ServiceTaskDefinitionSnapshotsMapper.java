package io.agentflow.servicetask.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ServiceTaskDefinitionSnapshots 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ServiceTaskDefinitionSnapshotsMapper {
    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("key") String key,
            @Param("version") Long version);
}
