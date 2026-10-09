package io.agentflow.expense.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcExpenseRequestRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseRequestRepositoryMapper {
    /** 读取 create 所需的持久化事实。 */
    List<String> create(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenantId") String tenantId, @Param("id") String id);
}
