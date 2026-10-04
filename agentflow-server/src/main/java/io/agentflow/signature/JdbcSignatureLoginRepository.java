package io.agentflow.signature;

import io.agentflow.auth.DeferredActorAuthentication.Kind;
import io.agentflow.auth.DeferredActorAuthentication.LoginReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 原登录引用与首次签署创建共用事务，缺少引用的历史待发操作不会被自动补授权。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSignatureLoginRepository {
    private final JdbcTemplate jdbc;
    /** 会话本体由认证仓储管理，业务历史不阻止会话清理。 */
    public JdbcSignatureLoginRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 只创建一次；不提供更换为其他登录或重新授权的更新入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(SignatureOperation operation, LoginReference reference) {
        if (operation.version() != 1 || operation.status() != SignatureOperation.Status.QUEUED) throw new IllegalArgumentException("Original queued authorization required");
        var request = operation.input().request();
        jdbc.update("INSERT INTO signature_login_authorization(tenant_id,operation_id,authentication_kind,login_reference) VALUES(?,?,?,?)",
                request.tenantId(), request.id().toString(), reference.kind().name(), reference.value());
    }
    /** 限定原操作及租户；业务层拿到引用后仍须向认证源复核。 */
    public Optional<LoginReference> find(SignatureOperation operation) {
        var request = operation.input().request();
        return jdbc.query("SELECT authentication_kind,login_reference FROM signature_login_authorization WHERE tenant_id=? AND operation_id=?",
                (row, index) -> new LoginReference(Kind.valueOf(row.getString(1)), row.getString(2)), request.tenantId(), request.id().toString()).stream().findFirst();
    }
}
