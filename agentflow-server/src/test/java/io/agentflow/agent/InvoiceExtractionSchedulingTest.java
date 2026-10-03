package io.agentflow.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 关闭外部模型时本地 XML 仍可调度，抽取轮询也可独立关闭。
 * @author owlzhangfq@gmail.com
 */
class InvoiceExtractionSchedulingTest {
    @Test void localExtractionSchedulerDoesNotRequireModelEnabled() {
        new ApplicationContextRunner().withUserConfiguration(InvoiceExtractionScheduling.class)
                .withBean(InvoiceExtractionWorker.class, () -> mock(InvoiceExtractionWorker.class))
                .withPropertyValues("agentflow.assist.enabled=false")
                .run(context -> assertThat(context).hasBean("invoiceExtractionTaskScheduler").hasBean("invoiceExtractionPoller"));
    }
    @Test void workerCanBeDisabledWithoutDisablingHistoricalReads() {
        new ApplicationContextRunner().withUserConfiguration(InvoiceExtractionScheduling.class)
                .withPropertyValues("agentflow.invoices.extraction-worker-enabled=false", "agentflow.assist.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("invoiceExtractionTaskScheduler").doesNotHaveBean("invoiceExtractionPoller"));
    }
}
