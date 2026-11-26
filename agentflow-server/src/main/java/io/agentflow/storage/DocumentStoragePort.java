package io.agentflow.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 不可变文档二进制存储端口，隔离本地文件系统与 S3/MinIO 对象存储实现。
 * 调用方继续负责租户、业务归属与访问授权，存储层仅维护字节完整性与内容指纹。
 * @author owlzhangfq@gmail.com
 */
public interface DocumentStoragePort {
    /** 当前后端是否已显式配置启用。 */
    boolean enabled();

    /** 允许写入的单文件最大字节数。 */
    long maxFileBytes();

    /** 标识当前生效的存储后端类型（如 local 或 s3）。 */
    String backendType();

    /** 流式限长暂存并校验内容指纹；不符时立即清理临时文件并拒绝发布。 */
    Path stage(LocalDocumentStore.Content file, InputStream input);

    /** 将已核验指纹的暂存文件以不可变方式发布至稳定存储键。 */
    void publish(LocalDocumentStore.Content file, Path staged);

    /** 读取并重新校验字节完整性，损坏或缺失时抛出领域存储异常。 */
    byte[] read(LocalDocumentStore.Content file);

    /** 仅当对象明确不存在时返回空，损坏或网络故障仍按异常拒绝。 */
    Optional<byte[]> readIfPresent(LocalDocumentStore.Content file);

    /** 清理尚未发布的临时暂存文件。 */
    void discard(Path staged);

    /** 检查当前存储后端是否可读写（供系统自检与就绪探针使用）。 */
    boolean available();
}
