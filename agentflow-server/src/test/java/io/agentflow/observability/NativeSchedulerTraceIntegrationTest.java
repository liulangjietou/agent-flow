package io.agentflow.observability;

import io.agentflow.approval.process.FlowableTaskDeadlineListener;
import io.agentflow.approval.process.FlowableTaskDeadlineReminders;
import io.agentflow.approval.process.FlowableTaskEscalations;
import io.agentflow.approval.process.TaskEscalationBindings;
import io.agentflow.approval.process.TimerWaitService;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 原生任务创建、队列迁移和事务回滚共同验证来源，禁止用当前请求补写旧任务。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:native-scheduler-trace;DB_CLOSE_DELAY=-1",
        "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
class NativeSchedulerTraceIntegrationTest {
    @Autowired RepositoryService definitions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired ManagementService jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FlowableTaskDeadlineReminders reminders;
    @Autowired FlowableTaskEscalations escalations;
    @Autowired TimerWaitService waits;

    @Test void taskCreationOriginSurvivesAssignmentAndBothTaskScannersUseItsNativeTenant() {
        String tenant = "trace-" + UUID.randomUUID(); String original = UUID.randomUUID().toString();
        String instance;
        try (var scope = new DiagnosticContext(original, "untrusted-context-tenant").open()) { instance = start(tenant); }
        var task = tasks.createTaskQuery().processInstanceId(instance).singleResult();
        Instant due = Instant.now().minusSeconds(60);
        try (var scope = new DiagnosticContext(UUID.randomUUID().toString(), "other").open()) {
            tasks.setAssignee(task.getId(), "reviewer"); tasks.setDueDate(task.getId(), Date.from(due));
            tasks.setVariableLocal(task.getId(), FlowableTaskDeadlineListener.CALENDAR_ID, UUID.randomUUID().toString());
            tasks.setVariableLocal(task.getId(), TaskEscalationBindings.DUE_AT, Date.from(due));
        }
        assertThat(reminders.candidates(Instant.now(), null)).filteredOn(value -> value.taskId().equals(task.getId()))
                .singleElement().extracting("traceId", "tenantId").containsExactly(original, tenant);
        assertThat(escalations.candidates(Instant.now(), null)).filteredOn(value -> value.taskId().equals(task.getId()))
                .singleElement().extracting("traceId", "tenantId").containsExactly(original, tenant);
        assertThat(origin(tenant, "TASK", task.getId())).isEqualTo(original);
    }

    @Test void timerKeepsCreationOriginAcrossPauseAndFailedQueueAndNextTaskGetsItsOwnCause() {
        String tenant = "trace-" + UUID.randomUUID(); String instance = start(tenant);
        var task = tasks.createTaskQuery().processInstanceId(instance).singleResult();
        String cause = UUID.randomUUID().toString();
        try (var scope = new DiagnosticContext(cause, tenant).open()) { tasks.complete(task.getId()); }
        var timer = jobs.createTimerJobQuery().processInstanceId(instance).singleResult();
        runtime.suspendProcessInstanceById(instance); runtime.activateProcessInstanceById(instance);
        var restored = jobs.createTimerJobQuery().processInstanceId(instance).singleResult();
        assertThat(restored.getId()).isEqualTo(timer.getId());
        assertThat(waits.candidates(timer.getDuedate().toInstant(), null)).filteredOn(value -> value.jobId().equals(timer.getId()))
                .singleElement().extracting("traceId", "tenantId").containsExactly(cause, tenant);
        String retry = UUID.randomUUID().toString();
        try (var scope = new DiagnosticContext(retry, tenant).open()) {
            var executable = jobs.moveTimerToExecutableJob(timer.getId());
            var failed = jobs.moveJobToDeadLetterJob(executable.getId());
            var recovered = jobs.moveDeadLetterJobToExecutableJob(failed.getId(), 1);
            assertThat(recovered.getId()).isEqualTo(timer.getId()); jobs.executeJob(recovered.getId());
        }
        assertThat(origin(tenant, "TIMER", timer.getId())).isEqualTo(cause);
        var next = tasks.createTaskQuery().processInstanceId(instance).singleResult();
        assertThat(next.getId()).isNotEqualTo(task.getId()); assertThat(origin(tenant, "TASK", next.getId())).isEqualTo(retry);
    }

    @Test void originsRollBackWithBothTaskCreationAndTimerTransition() {
        String tenant = "trace-" + UUID.randomUUID();
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> { start(tenant); status.setRollbackOnly(); });
        assertThat(runtime.createProcessInstanceQuery().processInstanceTenantId(tenant).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_execution_origin WHERE tenant_id=?", Integer.class, tenant)).isZero();
        String instance = start(tenant); var task = tasks.createTaskQuery().processInstanceId(instance).singleResult();
        tx.executeWithoutResult(status -> { tasks.complete(task.getId()); status.setRollbackOnly(); });
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(jobs.createTimerJobQuery().processInstanceId(instance).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_execution_origin WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
    }

    @Test void legacyNativeTaskDoesNotBorrowAnotherTenantOrTheCurrentRequestOrigin() {
        String tenant = "trace-" + UUID.randomUUID(); String instance = start(tenant);
        var task = tasks.createTaskQuery().processInstanceId(instance).singleResult();
        jdbc.update("DELETE FROM workflow_execution_origin WHERE tenant_id=? AND object_kind='TASK' AND object_id=?", tenant, task.getId());
        jdbc.update("INSERT INTO workflow_execution_origin(tenant_id,object_kind,object_id,trace_id) VALUES(?,'TASK',?,?)", "foreign", task.getId(), UUID.randomUUID().toString());
        Instant due = Instant.now().minusSeconds(60); tasks.setDueDate(task.getId(), Date.from(due));
        tasks.setVariableLocal(task.getId(), FlowableTaskDeadlineListener.CALENDAR_ID, UUID.randomUUID().toString());
        try (var scope = new DiagnosticContext(UUID.randomUUID().toString(), "caller").open()) {
            assertThat(reminders.candidates(Instant.now(), null)).filteredOn(value -> value.taskId().equals(task.getId()))
                    .singleElement().extracting("traceId", "tenantId").containsExactly(null, tenant);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_execution_origin WHERE tenant_id=? AND object_id=?", Integer.class, tenant, task.getId())).isZero();
    }

    private String origin(String tenant, String kind, String id) {
        return jdbc.queryForObject("SELECT trace_id FROM workflow_execution_origin WHERE tenant_id=? AND object_kind=? AND object_id=?", String.class, tenant, kind, id);
    }

    private String start(String tenant) {
        String key = "trace" + UUID.randomUUID().toString().replace("-", "");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="synthetic">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/><userTask id="first"/><intermediateCatchEvent id="wait">
                      <timerEventDefinition><timeDuration>PT1S</timeDuration></timerEventDefinition>
                    </intermediateCatchEvent><userTask id="next"/><endEvent id="end"/>
                    <sequenceFlow id="a" sourceRef="start" targetRef="first"/>
                    <sequenceFlow id="b" sourceRef="first" targetRef="wait"/>
                    <sequenceFlow id="c" sourceRef="wait" targetRef="next"/>
                    <sequenceFlow id="d" sourceRef="next" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(key);
        var deployed = definitions.createDeployment().tenantId(tenant).addString(key + ".bpmn20.xml", xml).deploy();
        var definition = definitions.createProcessDefinitionQuery().deploymentId(deployed.getId()).singleResult();
        return runtime.createProcessInstanceBuilder().processDefinitionId(definition.getId()).tenantId(tenant).start().getId();
    }
}
