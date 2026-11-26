package io.agentflow.notification.mapper;

import io.agentflow.mybatis.SqlRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 动态通知收件绑定的 MyBatis SQL 映射。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface NotificationBindingMapper {
    SqlRow find(@Param("tenantId") String tenantId, @Param("recipientId") String recipientId, @Param("channel") String channel);
    SqlRow findById(@Param("tenantId") String tenantId, @Param("id") String id);
    List<SqlRow> listByTenant(@Param("tenantId") String tenantId);
    List<SqlRow> enabledChannels(@Param("tenantId") String tenantId);
    int insert(@Param("id") String id, @Param("tenantId") String tenantId, @Param("recipientId") String recipientId,
               @Param("channel") String channel, @Param("serverId") String serverId, @Param("address") String address,
               @Param("enabled") boolean enabled, @Param("version") long version, @Param("updatedBy") String updatedBy,
               @Param("createdAt") OffsetDateTime createdAt, @Param("updatedAt") OffsetDateTime updatedAt);
    int update(@Param("id") String id, @Param("tenantId") String tenantId, @Param("recipientId") String recipientId,
               @Param("channel") String channel, @Param("serverId") String serverId, @Param("address") String address,
               @Param("enabled") boolean enabled, @Param("expectedVersion") long expectedVersion, @Param("nextVersion") long nextVersion,
               @Param("updatedBy") String updatedBy, @Param("updatedAt") OffsetDateTime updatedAt);
    int deleteById(@Param("tenantId") String tenantId, @Param("id") String id);
}
