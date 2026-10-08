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
public interface SubmissionHistoryGapQueryMapper {

    /** 按 count 的筛选条件执行数据库查询。 */
    List<Long> countQuery(
            @Param("condition0") boolean condition0,
            @Param("condition1") boolean condition1,
            @Param("parameters") Object[] parameters);

    /** 读取 countRows 所需的数据库事实。 */
    List<SqlRow> countRows(
            @Param("condition0") boolean condition0,
            @Param("condition1") boolean condition1,
            @Param("parameters") Object[] parameters);
}
