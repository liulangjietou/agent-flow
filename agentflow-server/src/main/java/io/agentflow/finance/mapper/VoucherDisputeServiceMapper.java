package io.agentflow.finance.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/**
 * VoucherDisputeService 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface VoucherDisputeServiceMapper {
    /** 新增 resolve 所需的持久化事实。 */
    int resolve(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("aggregateVersion") Long aggregateVersion,
            @Param("applicationId") String applicationId,
            @Param("actorId") String actorId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") Timestamp occurredAt);
}
