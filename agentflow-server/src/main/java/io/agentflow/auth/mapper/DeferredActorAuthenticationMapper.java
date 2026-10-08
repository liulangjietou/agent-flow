package io.agentflow.auth.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * DeferredActorAuthentication 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DeferredActorAuthenticationMapper {
    /** 读取 capture 所需的持久化事实。 */
    List<SqlRow> capture(@Param("getId") Object getId);

    /** 读取 restoreSession 所需的持久化事实。 */
    List<SqlRow> restoreSession(
            @Param("primary") String primary, @Param("toEpochMilli") Object toEpochMilli);
}
