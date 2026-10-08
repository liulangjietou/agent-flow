package io.agentflow.signature;


import io.agentflow.auth.DeferredActorAuthentication.Kind;
import io.agentflow.auth.DeferredActorAuthentication.LoginReference;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.signature.mapper.SignatureLoginRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 原登录引用与首次签署创建共用事务，缺少引用的历史待发操作不会被自动补授权。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSignatureLoginRepository {
    private final SignatureLoginRepositoryMapper sqlMapper;

    /** 会话本体由认证仓储管理，业务历史不阻止会话清理。 */
    public JdbcSignatureLoginRepository(SignatureLoginRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 只创建一次；不提供更换为其他登录或重新授权的更新入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(SignatureOperation operation, LoginReference reference) {
        if (operation.version() != 1 || operation.status() != SignatureOperation.Status.QUEUED)
            throw new IllegalArgumentException("Original queued authorization required");
        var request = operation.input().request();
        sqlMapper.insert(
                request.tenantId(),
                request.id().toString(),
                reference.kind().name(),
                reference.value());
    }

    /** 限定原操作及租户；业务层拿到引用后仍须向认证源复核。 */
    public Optional<LoginReference> find(SignatureOperation operation) {
        var request = operation.input().request();
        return SqlRows.map(
                        sqlMapper.find(request.tenantId(), request.id().toString()),
                        row -> new LoginReference(Kind.valueOf(row.getString(1)), row.getString(2)))
                .stream()
                .findFirst();
    }
}
