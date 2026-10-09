package io.agentflow.finance;


import io.agentflow.common.DomainException;
import io.agentflow.finance.mapper.PaymentPersonnelMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 财务执行只面向当前法人内仍在用的人员；系统角色仍由认证入口校验，本地任职不授予角色。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentPersonnel {

    private final PaymentPersonnelMapper sqlMapper;

    /** 本地组织没有显式初始化时不把演示账号当作法人出纳。 */
    public PaymentPersonnel(PaymentPersonnelMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 人员、法人、部门、岗位及任职必须同时有效，其他法人的相同角色不能取得付款范围。 */
    public boolean eligible(String tenant, String user, UUID legalEntityId) {
        return Boolean.TRUE.equals(
                SqlRows.single(sqlMapper.eligible(tenant, user, legalEntityId.toString())));
    }

    /** 供应商出纳目录在数据库分页前使用真实任职范围，不接受客户端传入的法人列表。 */
    public List<UUID> legalEntities(String tenant, String user) {
        return SqlRows.map(
                sqlMapper.legalEntities(tenant, user), row -> UUID.fromString(row.getString("id")));
    }

    /** 筛选只展示当前有效任职法人名称，不向出纳公开完整组织目录。 */
    public List<LegalEntity> legalEntityOptions(String tenant, String user) {
        return SqlRows.map(
                sqlMapper.legalEntityOptions(tenant, tenant, user),
                row ->
                        new LegalEntity(
                                UUID.fromString(row.getString("id")), row.getString("name")));
    }

    /** 后台在领取和发送登记时重读当前组织，停用或结束任职后阻止新发送。 */
    public void requireEligible(String tenant, String user, UUID legalEntityId) {
        if (!eligible(tenant, user, legalEntityId)) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Payment actor must have an active appointment in the original legal entity");
    }

    /**
     * 当前出纳可用的法人筛选项。
     *
     * @author owlzhangfq@gmail.com
     */
    public record LegalEntity(UUID id, String name) {}
}
