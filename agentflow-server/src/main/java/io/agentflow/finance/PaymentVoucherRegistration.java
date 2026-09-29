package io.agentflow.finance;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 银行成功与付款凭证准备同事务登记，旧成功事实通过同一入口补建。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentVoucherRegistration {
    private final JdbcPaymentOperationRepository payments;
    private final VoucherSources sources;
    private final VoucherPreparationService preparations;
    /** 只编排本地持久记录，网络请求继续由现有凭证执行器处理。 */
    public PaymentVoucherRegistration(JdbcPaymentOperationRepository payments, VoucherSources sources, VoucherPreparationService preparations) {
        this.payments = payments; this.sources = sources; this.preparations = preparations;
    }
    /** 重复成功事件沿用原准备；退票及冲突由准备和发送守卫重新读取。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(PaymentOperationChanged event) {
        if (event.current().settleable()) preparations.paid(event.current());
    }
    /** 锁后重读原支付，不将扫描时的成功状态当作仍然可用。 */
    @Transactional
    public void recover(String tenant, UUID id) {
        var initial = payments.find(tenant, id).orElse(null); if (initial == null || !initial.settleable()) return;
        sources.lock(sources.reference(initial));
        var current = payments.find(tenant, id).orElseThrow();
        if (current.settleable()) preparations.paid(current);
    }
}
