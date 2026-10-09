package io.agentflow.approval.history.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL 映射。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AuditHistoryAdapterMapper {

    /** 按 read 的筛选条件执行数据库查询。 */
    List<SqlRow> readQuery(
            @Param("itemsCount") int itemsCount,
            @Param("hasTasks") boolean hasTasks,
            @Param("parameters") Object[] parameters);
}
