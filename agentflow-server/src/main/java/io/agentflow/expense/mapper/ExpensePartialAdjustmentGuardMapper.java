package io.agentflow.expense.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ExpensePartialAdjustmentGuard 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePartialAdjustmentGuardMapper {
    /** 读取 requireWholeAllowed 所需的持久化事实。 */
    List<String> requireWholeAllowed(
            @Param("tenant") String tenant, @Param("report") String report);

    /** 读取 requirePartialAllowed 所需的持久化事实。 */
    List<String> requirePartialAllowed(
            @Param("tenant") String tenant, @Param("report") String report);
}
