package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;
import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 补正回执的追加与租户隔离读取，不提供覆盖历史的操作。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseCorrectionMapper {
    /** 与采纳记录、费用保存及预检排队共同提交。 */
    int insert(@Param("tenant") String tenant, @Param("runId") String runId,
            @Param("reportId") String reportId, @Param("applicationId") String applicationId,
            @Param("applicationVersion") long applicationVersion, @Param("financialVersion") long financialVersion,
            @Param("precheckId") String precheckId, @Param("appliedBy") String appliedBy,
            @Param("appliedAt") Timestamp appliedAt);

    /** 只按当前租户及原解释检索。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("runId") String runId);
}
