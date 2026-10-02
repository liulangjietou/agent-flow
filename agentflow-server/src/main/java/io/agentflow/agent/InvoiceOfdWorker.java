package io.agentflow.agent;

import java.io.IOException;
import java.nio.file.Path;

/**
 * OFD 的文件包、DOM、字体、图像和整页绘制都在此独立 JVM 内完成，不启动 Spring。
 * @author owlzhangfq@gmail.com
 */
public final class InvoiceOfdWorker {
    static final String INPUT_FILE = "original.ofd";
    private InvoiceOfdWorker() { }

    /** 只接受可信父进程提供的可选字体清单；所有页面成功后才写出有序图片结果。 */
    public static void main(String[] args) {
        try {
            if (args.length > 1) throw new IOException("OFD worker arguments are invalid");
            Path catalog = args.length == 0 ? null : Path.of(args[0]);
            var archive = InvoiceOfdArchive.read(Path.of(INPUT_FILE));
            var pages = InvoiceOfdRenderer.render(archive, catalog);
            InvoiceOfdResult.write(Path.of(InvoiceOfdResult.FILE), pages);
        } catch (Exception | LinkageError | StackOverflowError rejected) {
            // 原件内容、字体路径和第三方解析异常不写入父服务日志；OOM 由 JVM 退出策略处理。
            System.exit(1);
        }
    }
}
