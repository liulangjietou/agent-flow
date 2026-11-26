package io.agentflow.approval.mapper;

import io.agentflow.mybatis.SqlRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 申请催办记录 SQL 映射，参与催办事务内的冷却期检查与落库。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApplicationUrgeMapper {
    /** 查询当前租户申请当前轮次最近一次催办时间。 */
    List<SqlRow> findLatest(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") int roundNo);

    /** 新增一条催办记录。 */
    int insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") int roundNo,
            @Param("urgedBy") String urgedBy,
            @Param("note") String note,
            @Param("urgedAt") Timestamp urgedAt);
}
