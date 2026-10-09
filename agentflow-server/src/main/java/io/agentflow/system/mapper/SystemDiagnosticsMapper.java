package io.agentflow.system.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 运行时只读诊断查询，超时约束在 XML 中统一设置。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SystemDiagnosticsMapper {
    /** 验证业务数据库可查询。 */
    int database();

    /** 验证租户通知存储可查询。 */
    List<String> notifications(@Param("tenantId") String tenantId);

    /** 读取目录启用事实。 */
    List<Long> organization(@Param("tenantId") String tenantId);

    /** 读取已登记的组织来源标识。 */
    List<String> organizationSource(@Param("tenantId") String tenantId);

    /** 验证附件元数据和轮次关联可查询。 */
    List<String> attachments(@Param("tenantId") String tenantId);

    /** 验证共享会话表结构，不读取实际会话。 */
    List<SqlRow> sessions();
}
