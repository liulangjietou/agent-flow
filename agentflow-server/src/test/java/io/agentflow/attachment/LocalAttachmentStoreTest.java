package io.agentflow.attachment;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实文件系统验证损坏、软链接和重试，避免内存假实现掩盖原文件读取风险。
 * @author owlzhangfq@gmail.com
 */
class LocalAttachmentStoreTest {
    @Test
    void disabledStorageNeverFallsBackToAnEphemeralDirectory() {
        var store = new LocalAttachmentStore("", 1024, 2048, 3);
        assertThat(store.available()).isFalse();
        assertThatThrownBy(() -> new LocalAttachmentStore("relative", 1024, 2048, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalAttachmentStore("", LocalAttachmentStore.MAX_SUPPORTED_BYTES + 1, Long.MAX_VALUE, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void immutablePublishSurvivesRetryAndMissingTamperedOrLinkedFilesFailClosed() throws Exception {
        Path root = Files.createTempDirectory(Path.of("/fyoung/tmp"), "agentflow-attachment-store-");
        var store = new LocalAttachmentStore(root.toString(), 1024, 2048, 3);
        byte[] bytes = {1, 2, 3};
        var file = file(bytes);
        Path path = root.resolve(file.id() + ".bin");
        assertThatThrownBy(() -> store.read(file)).isInstanceOf(DomainException.class);
        for (int retry = 0; retry < 2; retry++) {
            Path staged = store.stage(file, new ByteArrayInputStream(bytes));
            store.publish(file, staged); store.discard(staged);
            assertThat(store.read(file)).isEqualTo(bytes);
        }
        try (var files = Files.list(root)) { assertThat(files.map(Path::getFileName).toList()).containsExactly(path.getFileName()); }
        Files.write(path, new byte[]{1, 2, 4});
        assertThatThrownBy(() -> store.read(file)).isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code()).isEqualTo("ATTACHMENT_INTEGRITY_FAILED"));
        Path staged = store.stage(file, new ByteArrayInputStream(bytes));
        assertThatThrownBy(() -> store.publish(file, staged)).isInstanceOf(DomainException.class);
        store.discard(staged);
        assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 4);
        Path external = root.resolve("external.bin"); Files.write(external, bytes);
        Files.delete(path); Files.createSymbolicLink(path, external);
        assertThatThrownBy(() -> store.read(file)).isInstanceOf(DomainException.class);
    }

    @Test
    void interruptedAndMismatchedBodiesLeaveNoStagingFiles() throws Exception {
        Path root = Files.createTempDirectory(Path.of("/fyoung/tmp"), "agentflow-attachment-failure-");
        var store = new LocalAttachmentStore(root.toString(), 1024, 2048, 3);
        var file = file(new byte[]{1, 2});
        assertThatThrownBy(() -> store.stage(file, new ByteArrayInputStream(new byte[]{1}))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> store.stage(file, new ByteArrayInputStream(new byte[]{1, 3}))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> store.stage(file, new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("Connection interrupted"); }
        })).isInstanceOf(DomainException.class);
        try (var files = Files.list(root)) { assertThat(files.toList()).isEmpty(); }
    }

    private Attachment file(byte[] bytes) throws Exception {
        return new Attachment(UUID.randomUUID(), "demo", UUID.randomUUID(), "proof", "验证.bin", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), "alice", Instant.now(), Attachment.Status.READY);
    }
}
