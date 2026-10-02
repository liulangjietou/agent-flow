package io.agentflow.agent;

import io.agentflow.auth.AuthService;
import io.agentflow.common.DomainException;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 票据所有人的有效资格供请求、领取和外发前复核，不要求审批角色。
 * @author owlzhangfq@gmail.com
 */
@Component
public class InvoiceExtractionEligibility {
    private final OrganizationRepository organization;
    private final AuthService demo;
    /** 已初始化租户只采用本地人员启停；演示租户沿用实际账号目录。 */
    public InvoiceExtractionEligibility(OrganizationRepository organization, AuthService demo) { this.organization = organization; this.demo = demo; }

    /** 人员停用或移除后不能发起、发送或确认个人抽取。 */
    public void requireActive(String tenant, String owner) {
        boolean active = organization.initialized(tenant)
                ? organization.personBySubject(tenant, owner).map(OrganizationPerson::active).orElse(false)
                : demo.activeAccount(tenant, owner);
        if (!active) throw new DomainException("FORBIDDEN", "Invoice extraction owner is inactive");
    }

    /** 与人员停用串行化，必须先于原件和运行锁取得。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant) { if (organization.initialized(tenant)) organization.lock(tenant); }
}
