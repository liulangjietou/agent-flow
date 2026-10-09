package io.agentflow.agent.mapper;

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
public interface AssistRunReadAdapterMapper {

    /** 按 list 的筛选条件执行数据库查询。 */
    List<SqlRow> listQuery(
            @Param("hasRoundNo") boolean hasRoundNo,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);
}
