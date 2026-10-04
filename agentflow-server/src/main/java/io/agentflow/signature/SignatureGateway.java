package io.agentflow.signature;

/**
 * 电子签防腐端口接收已持久领取的原操作，外发、查询和保存文件都在数据库事务外执行。
 * @author owlzhangfq@gmail.com
 */
public interface SignatureGateway {
    /** 仅供有效的首次发送领取使用，结果未知后调用方必须查询原号。 */
    ReceiptResult submit(SignatureOperation claim);
    /** 查询原签署身份，不重新发送原件，也不把 HTTP 404 当作可靠的业务回执。 */
    ReceiptResult query(SignatureOperation claim);
    /** 按已验真回执校验并独立保存预留结果；调用方随后在短事务中确认文件 READY。 */
    FileResult collect(SignatureOperation claim, SignatureReceiptVerifier.Evidence evidence, SignatureOperation.StoredArtifact file);

    /**
     * 网络观察和失败不自动提交业务状态。
     * @author owlzhangfq@gmail.com
     */
    sealed interface ReceiptResult permits Observed, Unavailable { }
    /**
     * 返回完整原始签名证据，调用方不能只保存解析出的事实。
     * @author owlzhangfq@gmail.com
     */
    record Observed(SignatureReceiptVerifier.Verified verified) implements ReceiptResult { }
    /**
     * 文件已校验保存和失败分开返回，不把预留标识当作真实文件。
     * @author owlzhangfq@gmail.com
     */
    sealed interface FileResult permits Stored, Unavailable { }
    /**
     * 只确认本次指定的不可变文件，不能替调用方确认其他文件或业务终态。
     * @author owlzhangfq@gmail.com
     */
    record Stored(SignatureOperation.StoredArtifact file) implements FileResult { }
    /**
     * 有界失败类别不携带令牌、服务地址或原始响应。
     * @author owlzhangfq@gmail.com
     */
    record Unavailable(SignatureOperation.Failure failure) implements ReceiptResult, FileResult { }
}
