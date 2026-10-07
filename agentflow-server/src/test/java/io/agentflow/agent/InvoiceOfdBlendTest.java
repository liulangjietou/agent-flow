package io.agentflow.agent;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实票面批注使用 Darken；混合必须作用于当前背景，不能静默当作普通覆盖。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdBlendTest {
    @TempDir Path directory;

    @Test
    void darkensPathAndTextChannelsWhileNormalKeepsSourceOver() throws Exception {
        var files = fixture(1);
        for (String mode : List.of("Darken", "Normal")) {
            page(files, 0, "", background() + path("0 0 10 10", "100 200 50", "BlendMode=\"" + mode + "\""));
            assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(new Color(100, mode.equals("Darken") ? 100 : 200, 50).getRGB());
        }
        page(files, 0, "", background() + text("BlendMode=\"Darken\"", "<ofd:FillColor Value=\"100 200 50\"/><ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        assertThat(pixel(render(files).get(0), 2, 4)).isEqualTo(new Color(100, 100, 50).getRGB());
    }

    @Test
    void imageAlphaAndObjectAlphaBothApplyToTheDarkenedColor() throws Exception {
        var files = fixture(1);
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, new Color(100, 200, 50, 128).getRGB());
        var png = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", png); image.flush();
        files.put("Doc_0/Res/picture.png", png.toByteArray());
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:MultiMedias><ofd:MultiMedia ID=\"11\" Type=\"Image\" Format=\"PNG\"><ofd:MediaFile>picture.png</ofd:MediaFile></ofd:MultiMedia></ofd:MultiMedias></ofd:Res>");
        page(files, 0, "", background() + "<ofd:ImageObject ID=\"20\" ResourceID=\"11\" Boundary=\"2 2 6 6\" CTM=\"6 0 0 6 0 0\" Alpha=\"128\" BlendMode=\"Darken\"/>");
        var color = new Color(pixel(render(files).get(0), 4, 4));
        assertThat(color.getRed()).isBetween(174, 176);
        assertThat(color.getGreen()).isBetween(99, 101);
        assertThat(color.getBlue()).isBetween(124, 126);
        assertThat(pixel(render(files).get(0), 1, 1)).isEqualTo(new Color(200, 100, 150).getRGB());
    }

    @Test
    void rejectsUnknownLaterBlendModesAndUnsupportedCompositeBlending() throws Exception {
        var files = fixture(2);
        for (String mode : List.of("Multiply", "Unknown", "")) {
            page(files, 1, "", path("0 0 10 10", "100 200 50", "BlendMode=\"" + mode + "\" Visible=\"false\""));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:CompositeGraphicUnits><ofd:CompositeGraphicUnit ID=\"11\" Width=\"10\" Height=\"10\"><ofd:Content>"
                + path("0 0 10 10", "100 200 50", "") + "</ofd:Content></ofd:CompositeGraphicUnit></ofd:CompositeGraphicUnits></ofd:Res>");
        page(files, 1, "", "<ofd:CompositeObject ID=\"21\" ResourceID=\"11\" Boundary=\"0 0 10 10\" BlendMode=\"Darken\"/>");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void emptyClipSequencesKeepObjectAndNestedClipBoundaries() throws Exception {
        var files = fixture(1);
        String object = path("2 2 5 5", "255 0 0", "");
        page(files, 0, "", object); byte[] expected = render(files).get(0);
        for (String flag : List.of("", " TransFlag=\"true\"", " TransFlag=\"false\"")) {
            page(files, 0, "", object.replace("</ofd:PathObject>", "<ofd:Clips" + flag + "/></ofd:PathObject>"));
            assertThat(render(files).get(0)).isEqualTo(expected);
        }
        String clipped = path("0 0 5 5", "255 0 0", "").replace("PathObject", "Path")
                .replace("</ofd:Path>", "<ofd:Clips/></ofd:Path>");
        String nested = "<ofd:Clips><ofd:Clip><ofd:Area>" + clipped + "</ofd:Area></ofd:Clip></ofd:Clips>";
        page(files, 0, "", path("0 0 10 10", "255 0 0", "").replace("</ofd:PathObject>", nested + "</ofd:PathObject>"));
        var page = render(files).get(0);
        assertThat(pixel(page, 4, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(page, 8, 8)).isEqualTo(Color.WHITE.getRGB());
    }

    private static String background() { return path("0 0 10 10", "200 100 150", ""); }
    private List<byte[]> render(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source));
    }
}
