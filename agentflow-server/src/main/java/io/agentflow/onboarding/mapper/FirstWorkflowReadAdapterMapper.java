package io.agentflow.onboarding.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcFirstWorkflowReadAdapter 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FirstWorkflowReadAdapterMapper {
    /** 读取 read 所需的持久化事实。 */
    List<Long> read(@Param("parameters") Object[] parameters);

    /** 读取 read 所需的持久化事实。 */
    List<Long> read2(@Param("parameters") Object[] parameters);

    /** 执行 read 的条件查询。 */
    List<SqlRow> readQuery(@Param("parameters") Object[] parameters);

    /** 执行 read 的条件查询。 */
    List<SqlRow> readQuery2(@Param("parameters") Object[] parameters);

    /** 执行 evidence 的条件查询。 */
    List<SqlRow> evidenceQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
