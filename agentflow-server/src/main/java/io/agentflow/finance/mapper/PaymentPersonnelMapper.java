package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * PaymentPersonnel 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentPersonnelMapper {
    /** 读取 eligible 所需的持久化事实。 */
    List<Boolean> eligible(
            @Param("tenant") String tenant,
            @Param("user") String user,
            @Param("legalEntityId") String legalEntityId);

    /** 读取 legalEntities 所需的持久化事实。 */
    List<SqlRow> legalEntities(@Param("tenant") String tenant, @Param("user") String user);

    /** 读取 legalEntityOptions 所需的持久化事实。 */
    List<SqlRow> legalEntityOptions(
            @Param("tenant") String tenant,
            @Param("tenantId") String tenantId,
            @Param("user") String user);
}
