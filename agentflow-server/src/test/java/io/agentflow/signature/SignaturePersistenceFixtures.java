package io.agentflow.signature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * 数据库范围夹具明确提供合成批准和文件元数据，不冒充真实审批、文件字节或签署验真。
 *
 * @author owlzhangfq@gmail.com
 */
final class SignaturePersistenceFixtures {
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00.123456789Z");
    static final Duration LEASE = Duration.ofSeconds(15);
    static final JsonUtil JSON = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private SignaturePersistenceFixtures() { }

    static JdbcSignatureOperationRepository repository(DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(new JdbcSignatureOperationRepository(
                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                        (new JdbcTemplate(source)).getDataSource(),
                                        io.agentflow.signature.mapper
                                                .SignatureOperationRepositoryMapper.class), JSON));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (JdbcSignatureOperationRepository) proxy.getProxy();
    }

    static void migrate(DataSource source, String version) { Flyway.configure().dataSource(source).target(version).load().migrate(); }

    static SignatureOperation seed(JdbcTemplate jdbc) {
        UUID application = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var request = new SignatureRequest(UUID.randomUUID(), "tenant-a", new SignatureRequest.Source(application, 2, 7, "contract", 3),
                new SignatureRequest.Authorization("alice", "company-seal", 2, "c".repeat(64), "合同签署授权", NOW, NOW.plusSeconds(3600)),
                List.of(new SignatureRequest.Document(first, first, "contract", "合同.pdf", 1001, "a".repeat(64)),
                        new SignatureRequest.Document(second, second, "items.attachment", "补充协议.pdf", 2002, "b".repeat(64))),
                List.of(new SignatureRequest.Signer("company", "provider-company-1")));
        seed(jdbc, request);
        return SignatureOperation.queue(new SignatureOperation.Input(request, "d".repeat(64)), NOW);
    }

    static void seed(JdbcTemplate jdbc, SignatureRequest request) {
        var source = request.source();
        jdbc.update(
                """
INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
VALUES(?,?,?,?,?,'alice','合成已批准合同','{}','APPROVED',?,?)
""", source.applicationId().toString(), request.tenantId(), "SIGN-" + source.applicationId(), source.processKey(), source.definitionVersion(), source.roundNo(), source.applicationVersion());
        jdbc.update(
                """
INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,completed_by,completed_at)
VALUES(?,?,?,?,?,'合成批准轮次','{}','alice',?,'APPROVED','manager',?)
""", request.tenantId(), source.applicationId().toString(), source.roundNo(), "signature-instance-" + source.applicationId(), source.definitionVersion(), Timestamp.from(NOW.minusSeconds(100)), Timestamp.from(NOW.minusSeconds(50)));
        for (var document : request.documents()) {
            jdbc.update(
                    """
INSERT INTO approval_attachment(id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status,content_id)
VALUES(?,?,?,?,?,?,?,'alice',?,'READY',?)
""", document.attachmentId().toString(), request.tenantId(), source.applicationId().toString(), document.fieldPath(), document.filename(), document.size(), document.sha256(),
                    Timestamp.from(NOW.minusSeconds(110)), document.contentId().equals(document.attachmentId()) ? null : document.contentId().toString());
            jdbc.update(
                    "INSERT INTO"
                        + " approval_attachment_round(tenant_id,application_id,round_no,field_path,attachment_id)"
                        + " VALUES(?,?,?,?,?)",
                    request.tenantId(), source.applicationId().toString(), source.roundNo(), document.fieldPath(), document.attachmentId().toString());
        }
    }

    static SignatureOperation another(SignatureOperation operation) {
        var old = operation.input().request();
        return SignatureOperation.queue(new SignatureOperation.Input(new SignatureRequest(UUID.randomUUID(), old.tenantId(), old.source(), old.authorization(), old.documents(), old.signers()), operation.input().targetDigest()), NOW);
    }

    static SignatureReceipt receipt(SignatureOperation operation, SignatureReceipt.Status status, long revision, Instant time) {
        var request = operation.input().request();
        boolean terminal = status == SignatureReceipt.Status.SIGNED || status == SignatureReceipt.Status.DECLINED || status == SignatureReceipt.Status.CANCELLED;
        var artifacts = status == SignatureReceipt.Status.SIGNED ? request.documents().stream().map(document -> new SignatureReceipt.Artifact(document.attachmentId(), document.size() + 400,
                "e".repeat(64), "application/pdf", List.of(new SignatureReceipt.Proof("company", "f".repeat(64), time, null)))).toList() : List.<SignatureReceipt.Artifact>of();
        return new SignatureReceipt(request.id(), request.digest(), status == SignatureReceipt.Status.NOT_FOUND ? 0 : revision, status, time,
                status == SignatureReceipt.Status.NOT_FOUND ? null : "provider-signature-1", terminal ? time : null, artifacts);
    }
    /** 固定 V111—V123 的旧行格式，迁移验收不能调用依赖最新列的创建方法。 */
    static void createLegacyOperation(JdbcTemplate db, SignatureOperation operation) {
        var input = operation.input(); var request = input.request(); var source = request.source();
        db.update(
                """
INSERT INTO signature_operation(tenant_id,id,application_id,round_no,request_digest,target_digest,input_json,state_json,
    version,status,attempts,authorized_at,updated_at,next_attempt_at,poll_at,active_guard)
VALUES(?,?,?,?,?,?,?,?,1,'QUEUED',0,?,?,?,?,1)
""", request.tenantId(), request.id().toString(), source.applicationId().toString(), source.roundNo(), request.digest(), input.targetDigest(),
                JSON.write(input), JSON.write(operation), legacyTime(request.authorization().authorizedAt()), legacyTime(operation.updatedAt()),
                legacyTime(operation.nextAttemptAt()), legacyTime(operation.nextAttemptAt()));
        for (var document : request.documents()) db.update(
                    """
INSERT INTO signature_source_document(tenant_id,operation_id,application_id,round_no,attachment_id,original_content_id,
    field_path,filename,byte_size,sha256,original_status) VALUES(?,?,?,?,?,?,?,?,?,?,'READY')
""", request.tenantId(), request.id().toString(), source.applicationId().toString(), source.roundNo(), document.attachmentId().toString(),
                document.contentId().toString(), document.fieldPath(), document.filename(), document.size(), document.sha256());
        db.update(
                "INSERT INTO"
                    + " signature_operation_revision(tenant_id,operation_id,version,state_json,occurred_at)"
                    + " VALUES(?,?,1,?,?)",
                request.tenantId(), request.id().toString(), JSON.write(operation), legacyTime(operation.updatedAt()));
    }
    private static java.sql.Timestamp legacyTime(java.time.Instant value) {
        return java.sql.Timestamp.from(value.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
}
