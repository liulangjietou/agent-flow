package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import org.apache.commons.lang3.StringUtils;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 查验实际原件的只读端口；文件摘要绑定响应，查验状态不能由页面提交。
 * @author owlzhangfq@gmail.com
 */
public interface InvoiceVerificationPort {
    /** 真实原件由服务器存储读取，不能传入客户端指定的下载地址。 */
    FinanceResult<Invoice.VerifiedFacts> verify(String tenantId, Request request);

    /**
     * 受控原件内容，限制体积并核对字节摘要；不接受远程文件 URL。
     * @author owlzhangfq@gmail.com
     */
    record Request(String employeeId, UUID legalEntityId, UUID originalFileId, String originalDigest, String mediaType, byte[] original) {
        public static final int MAX_ORIGINAL_BYTES = 20 * 1024 * 1024;
        private static final Set<String> MEDIA_TYPES = Set.of("application/pdf", "application/ofd", "image/png", "image/jpeg", "application/xml");

        /** 防御性复制保留授权时的字节，文件类型的内容识别由原件上传入口完成。 */
        public Request {
            if (StringUtils.isBlank(employeeId) || employeeId.length() > 128 || legalEntityId == null || originalFileId == null
                    || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}") || !MEDIA_TYPES.contains(mediaType == null ? "" : mediaType)
                    || original == null || original.length == 0 || original.length > MAX_ORIGINAL_BYTES) throw invalid();
            original = original.clone();
            try {
                if (!originalDigest.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)))) throw invalid();
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        }

        /** 调用方不能在校验后改写原件字节。 */
        @Override public byte[] original() { return original.clone(); }

        /** 不允许错误日志通过自动 record 文本输出原件或员工信息。 */
        @Override public String toString() { return "InvoiceVerificationRequest[original=redacted]"; }

        private static DomainException invalid() { return new DomainException("INVALID_INVOICE_ORIGINAL", "A supported original file with matching digest is required"); }
    }
}
