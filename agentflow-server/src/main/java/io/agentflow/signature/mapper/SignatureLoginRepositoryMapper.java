package io.agentflow.signature.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcSignatureLoginRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SignatureLoginRepositoryMapper {
    /** 新增 insert 所需的持久化事实。 */
    int insert(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("authenticationKind") String authenticationKind,
            @Param("loginReference") String loginReference);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenantId") String tenantId, @Param("id") String id);
}
