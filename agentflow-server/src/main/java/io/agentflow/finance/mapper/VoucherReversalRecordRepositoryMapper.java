package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcVoucherReversalRecordRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface VoucherReversalRecordRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("operationId") String operationId,
            @Param("operationVersion") Long operationVersion,
            @Param("checkId") String checkId,
            @Param("checkVersion") Long checkVersion,
            @Param("legalEntityId") String legalEntityId,
            @Param("reversalPostingReference") String reversalPostingReference,
            @Param("reversalVoucherReference") String reversalVoucherReference,
            @Param("recordedBy") String recordedBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("recordedAt") Timestamp recordedAt,
            @Param("stateJson") String stateJson);

    /** 读取 forOperation 所需的持久化事实。 */
    List<SqlRow> forOperation(
            @Param("tenant") String tenant, @Param("operationId") String operationId);
}
