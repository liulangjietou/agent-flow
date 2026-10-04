package io.agentflow.signature;

import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.storage.LocalDocumentStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 浏览器只取得经当前权限筛选的业务视图；不序列化内部授权、目标、回执或存储身份。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SignaturePublicService {
    private final CurrentActor actors;
    private final SignatureAccess access;
    private final SignatureOperationService service;
    private final JdbcSignatureOperationRepository operations;
    private final DeferredActorAuthentication authentication;
    private final LocalDocumentStore documents;

    /** 写用例仍由原事务服务执行，这一层负责页面选择、分页和安全下载投影。 */
    public SignaturePublicService(CurrentActor actors, SignatureAccess access, SignatureOperationService service,
            JdbcSignatureOperationRepository operations, DeferredActorAuthentication authentication, LocalDocumentStore documents) {
        this.actors = actors; this.access = access; this.service = service; this.operations = operations;
        this.authentication = authentication; this.documents = documents;
    }

    /** 不返回资料的实际服务账户、地址、凭据、公钥及其他授权人。 */
    public Options options() {
        var profiles = access.profiles(actors.actor()).stream().map(value -> new ProfileOption(value.key(), Long.toString(value.version()), value.name())).toList();
        return new Options(authentication.available() && documents.available() && !profiles.isEmpty(), profiles,
                SignatureRequest.MAX_DOCUMENTS, SignatureRequest.MAX_DOCUMENT_BYTES, SignatureRequest.MAX_TOTAL_BYTES,
                SignatureRequest.MAX_AUTHORIZATION_LIFETIME.toSeconds());
    }

    /** 幂等命中也需要这次的字段权限；检查不发送文件或重新创建签署授权。 */
    public List<SignatureRequest.Document> preflight(UUID application, SignatureAccess.CreateInput input) {
        return access.requireSelection(actors.actor(), application, input.roundNo(), input.documentIds());
    }

    /** 新请求在幂等写事务外核对原件字节；成功重放不重新要求物理存储仍在线。 */
    public void requireOriginalsAvailable(List<SignatureRequest.Document> originals) {
        if (!authentication.available()) throw new DomainException("DEFERRED_AUTHENTICATION_UNAVAILABLE", "Deferred execution authentication is unavailable");
        if (!documents.available()) throw new DomainException("FILE_STORAGE_UNAVAILABLE", "Document storage is unavailable");
        for (var original : originals) documents.read(new LocalDocumentStore.Content(original.contentId(), original.size(), original.sha256()));
    }

    /** 一页最多扫描指定数量的操作；不可读项不返回，空页仍可用下一游标继续。 */
    public Page list(UUID application, int roundNo, UUID afterId, int limit) {
        var actor = actors.actor(); access.requireRound(actor, application, roundNo);
        SignatureOperation after = afterId == null ? null : operations.find(actor.tenantId(), afterId)
                .filter(value -> value.input().request().source().applicationId().equals(application) && value.input().request().source().roundNo() == roundNo)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Signature cursor not found in this round"));
        var rows = operations.forRound(actor.tenantId(), application, roundNo, after, limit + 1);
        var visible = new ArrayList<Receipt>(); int count = Math.min(rows.size(), limit);
        for (var operation : rows.subList(0, count)) {
            try { access.requireReadable(actor, operation); visible.add(Receipt.of(operation)); }
            catch (DomainException denied) { if (!denied.code().equals("FORBIDDEN") && !denied.code().equals("NOT_FOUND")) throw denied; }
        }
        UUID cursor = rows.size() > limit ? rows.get(count - 1).input().request().id() : null;
        return new Page(List.copyOf(visible), cursor);
    }

    /** 状态、授权说明和文件名仅在全部原件当前可读时展示，不返回内部内容编号。 */
    public View detail(UUID application, UUID id) {
        var operation = service.get(application, id); var request = operation.input().request(); var authorization = request.authorization();
        boolean signed = operation.status() == SignatureOperation.Status.SIGNED;
        var files = request.documents().stream().map(document -> {
            Long size = signed ? operation.artifacts().stream().filter(file -> file.documentId().equals(document.attachmentId())).findFirst().orElseThrow().size() : null;
            return new DocumentView(document.attachmentId(), document.filename(), document.size(), size, signed);
        }).toList();
        return new View(Receipt.of(operation), request.source().roundNo(), authorization.profileKey(), Long.toString(authorization.profileVersion()),
                authorization.actor(), authorization.purpose(), authorization.authorizedAt(), authorization.validUntil(), operation.updatedAt(), operation.nextAttemptAt(),
                operation.failure(), operation.status() == SignatureOperation.Status.QUEUED && authorization.actor().equals(actors.actor().userId()), files);
    }

    /** 只下载已完整完成的结果；权限和字节指纹每次重查，预留或部分保存不能冒充交付。 */
    public Download download(UUID application, UUID id, UUID documentId) {
        var operation = service.get(application, id);
        if (operation.status() != SignatureOperation.Status.SIGNED) throw new DomainException("SIGNATURE_RESULT_NOT_READY", "Signed results are not complete");
        var original = operation.input().request().documents().stream().filter(value -> value.attachmentId().equals(documentId)).findFirst()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Signed document not found"));
        var result = operation.artifacts().stream().filter(value -> value.documentId().equals(documentId)).findFirst().orElseThrow();
        return new Download("signed-" + original.filename(), documents.read(new LocalDocumentStore.Content(result.contentId(), result.size(), result.sha256())));
    }

    /**
     * 成功写入的幂等回执只包含操作号、完整文本版本和当时状态。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, String version, SignatureOperation.Status status) {
        /** 固定响应不复制业务说明或任何内部授权资料。 */
        public static Receipt of(SignatureOperation operation) { return new Receipt(operation.input().request().id(), Long.toString(operation.version()), operation.status()); }
    }
    /**
     * 游标仅是签署操作位置，不包含原件身份；可读项不足一页时也不能提前判断列表结束。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Receipt> items, UUID nextAfterId) { }
    /**
     * 资料的公开名称和固定版本，用于明确选择本次签署授权。
     * @author owlzhangfq@gmail.com
     */
    public record ProfileOption(String key, String version, String name) { }
    /**
     * 实际部署可用性及固定文件限制，不暴露认证和存储配置。
     * @author owlzhangfq@gmail.com
     */
    public record Options(boolean enabled, List<ProfileOption> profiles, int maxDocuments, long maxDocumentBytes, long maxTotalBytes, long maxAuthorizationSeconds) { }
    /**
     * 文件列表只使用用户已授权的原件引用，结果内容身份保留在服务端。
     * @author owlzhangfq@gmail.com
     */
    public record DocumentView(UUID id, String filename, long originalBytes, Long signedBytes, boolean downloadable) { }
    /**
     * 详情不携带服务方签署账户、记录号、原始签名、公钥、目标或登录引用。
     * @author owlzhangfq@gmail.com
     */
    public record View(Receipt operation, int roundNo, String profileKey, String profileVersion, String authorizedBy, String purpose,
                       Instant authorizedAt, Instant validUntil, Instant updatedAt, Instant nextAttemptAt, SignatureOperation.Failure failure,
                       boolean canCancel, List<DocumentView> documents) { }
    /**
     * 已经过当前权限和本地字节完整性核对的结果下载。
     * @author owlzhangfq@gmail.com
     */
    public record Download(String filename, byte[] content) { }
}
