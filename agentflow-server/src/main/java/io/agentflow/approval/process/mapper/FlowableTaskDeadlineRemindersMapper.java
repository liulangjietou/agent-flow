package io.agentflow.approval.process.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * FlowableTaskDeadlineReminders 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FlowableTaskDeadlineRemindersMapper {
    /** 读取 remind 所需的持久化事实。 */
    List<String> remind(@Param("taskId") String taskId);

    /** 按 candidates 的筛选条件执行数据库查询。 */
    List<SqlRow> candidatesQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
