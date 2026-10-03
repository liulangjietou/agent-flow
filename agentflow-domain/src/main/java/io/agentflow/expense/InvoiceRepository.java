package io.agentflow.expense;

import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 发票原件、查验和占用的持久端口，租户内唯一占用由数据库保证。
 * @author owlzhangfq@gmail.com
 */
public interface InvoiceRepository {
    /** 保存尚未查验的原件身份。 */
    void create(Invoice invoice, String actor);
    /** 原子保存查验或占用并追加版本记录。 */
    void update(Invoice invoice, long expectedVersion, String actor, String operation);
    /** 仅查询当前租户的发票。 */
    Optional<Invoice> find(String tenantId, UUID id);
    /** 批量读取当前及前一轮引用，避免按发票逐条查询数据库。 */
    Map<UUID, Invoice> findAll(String tenantId, Collection<UUID> ids);
}
