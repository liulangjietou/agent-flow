package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/**
 * 用实际安装包中的父适配器和子进程处理外部合成原件，不把测试字体打入安装包。
 * @author owlzhangfq@gmail.com
 */
public final class InvoiceOfdPackagingProbe {
    private InvoiceOfdPackagingProbe() { }

    /** 四个参数依次为临时根、原件、字体清单或短横线、成功输出目录；父探针不解析原件。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected temporary root, source, catalog and output");
        Path temporary = Path.of(args[0]), output = Path.of(args[3]);
        byte[] source = Files.readAllBytes(Path.of(args[1]));
        var pages = new InvoiceOfdInspector(temporary, Duration.ofSeconds(30))
                .render(source, args[2].equals("-") ? null : Path.of(args[2]));
        var images = new ArrayList<Map<String, Object>>();
        Files.createDirectories(output);
        for (int i = 0; i < pages.size(); i++) {
            Path file = Files.write(output.resolve("page-" + (i + 1) + ".png"), pages.get(i));
            images.add(Map.of("file", file.toString(), "bytes", pages.get(i).length,
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pages.get(i)))));
        }
        try (var remaining = Files.list(temporary)) {
            if (remaining.findAny().isPresent()) throw new IllegalStateException("Owned process directory was not cleaned");
        }
        if (ProcessHandle.current().children().anyMatch(ProcessHandle::isAlive)) throw new IllegalStateException("Worker is still alive");
        var result = Map.of("pages", pages.size(), "images", images, "sourceBytes", source.length,
                "sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)),
                "temporaryRootEmpty", true, "childProcessesStopped", true);
        Files.writeString(output.resolve("result.json"), new JsonUtil(new ObjectMapper()).write(result));
        System.out.println("OFD_PACKAGED_PROBE_OK pages=" + pages.size());
    }
}
