package io.agentflow.approval.process.mapper;

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
public interface TimerWaitServiceMapper {

    /** 按 candidates 的筛选条件执行数据库查询。 */
    List<SqlRow> candidatesQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
