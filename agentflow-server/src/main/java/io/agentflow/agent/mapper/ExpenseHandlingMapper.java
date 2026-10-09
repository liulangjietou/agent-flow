package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 单据和本人外键约束办理范围，每次转换追加历史。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseHandlingMapper {
    /** 新建与本人费用一一绑定的活动记录。 */
    int insert(@Param("tenant") String tenant, @Param("id") String id, @Param("report") String report,
            @Param("application") String application, @Param("owner") String owner, @Param("context") String context, @Param("state") String state);
    /** 版本条件更新并释放终态活动位置。 */
    int update(@Param("tenant") String tenant, @Param("id") String id, @Param("previous") long previous,
            @Param("version") long version, @Param("active") String active, @Param("state") String state);
    /** 有界读取本单历史。 */
    List<SqlRow> list(@Param("tenant") String tenant, @Param("report") String report);
    /** 精确读取指定运行。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);
    /** 活动记录在同一业务事务中串行更新。 */
    List<SqlRow> active(@Param("tenant") String tenant, @Param("report") String report);
    /** 原状态只追加不覆盖。 */
    int append(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version, @Param("state") String state);
}
