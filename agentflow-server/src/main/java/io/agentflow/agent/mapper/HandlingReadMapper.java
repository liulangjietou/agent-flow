package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 读取执行始终受办理聚合的租户外键约束。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface HandlingReadMapper {
    /** 同单据锁下创建原执行。 */
    int insert(@Param("tenant") String tenant, @Param("id") String id, @Param("task") String task,
            @Param("input") String input, @Param("state") String state);
    /** 锁定同一个原步骤。 */
    List<SqlRow> lock(@Param("tenant") String tenant, @Param("id") String id);
    /** 列出有界执行及失败状态。 */
    List<SqlRow> list(@Param("tenant") String tenant, @Param("task") String task);
    /** 防止旧租约的迟到结果覆盖新状态。 */
    int update(@Param("tenant") String tenant, @Param("id") String id, @Param("previous") long previous,
            @Param("version") long version, @Param("state") String state);
    /** 每次转换保留原始版本。 */
    int append(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version, @Param("state") String state);
}
