package io.agentflow.agent;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import static io.agentflow.agent.InvoiceOfdXml.invalid;

/**
 * 正文和签章共用的有界图像解码，保留原透明像素并拒绝截断后交出的部分图像。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdImages {
    private static final long MAX_IMAGE_PIXELS = 20_000_000;
    private static final int MAX_IMAGE_SIDE = 12_000;
    private InvoiceOfdImages() { }

    /** 调用方持有输入流及返回图像的生命周期；声明格式存在时必须与真实编码一致。 */
    static BufferedImage read(InputStream input, String declaredFormat) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!Set.of("PNG", "JPEG").contains(format)) throw invalid();
                if (declaredFormat != null) {
                    String declared = declaredFormat.toUpperCase(Locale.ROOT);
                    if (declared.equals("JPG")) declared = "JPEG";
                    if (!declared.equals(format)) throw invalid();
                }
                reader.setInput(stream, true, true);
                var warned = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE || (long) width * height > MAX_IMAGE_PIXELS) throw invalid();
                BufferedImage result = reader.read(0);
                if (result == null || warned.get()) {
                    if (result != null) result.flush();
                    throw invalid();
                }
                return result;
            } finally { reader.dispose(); }
        }
    }
}
