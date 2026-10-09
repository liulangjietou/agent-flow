package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 编排状态与原步骤按租户和版本持久，领取和完成都使用原行锁。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseAgentMapper {
    /** 同办理只允许一次明确的自动授权。 */
    int insert(@Param("tenant") String tenant, @Param("id") String id, @Param("task") String task, @Param("report") String report,
            @Param("context") String context, @Param("state") String state);
    /** 读取或锁定原编排。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("task") String task, @Param("lock") boolean lock);
    /** 只领取可执行或需恢复的有界批次。 */
    List<SqlRow> candidates(@Param("now") Instant now);
    /** 等待人工的运行也轮换检查顺序，不能饿死新任务。 */
    int checked(@Param("tenant") String tenant, @Param("task") String task, @Param("now") Instant now);
    /** 原租约和版本一起更新，防止迟到结算。 */
    int update(@Param("tenant") String tenant, @Param("id") String id, @Param("previous") long previous,
            @Param("version") long version, @Param("status") String status, @Param("lease") Instant lease, @Param("state") String state);
    /** 审计状态只追加。 */
    int append(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version, @Param("state") String state);
    /** 模型调用前提交完整有界输入和原步骤身份。 */
    int modelCall(@Param("tenant") String tenant, @Param("id") String id, @Param("run") String run,
            @Param("report") String report, @Param("input") String input, @Param("created") Instant created);
    /** 抽取任务在原排队事务中绑定本人办理。 */
    int child(@Param("tenant") String tenant, @Param("task") String task, @Param("child") String child, @Param("kind") String kind,
            @Param("report") String report, @Param("application") long application, @Param("financial") long financial);
    /** 后台子任务以原编号定位唯一办理，不按当前页面猜测关联。 */
    List<SqlRow> childLinks(@Param("tenant") String tenant, @Param("child") String child, @Param("kind") String kind);
}
