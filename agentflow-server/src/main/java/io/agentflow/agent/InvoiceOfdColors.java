package io.agentflow.agent;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 解析基本颜色、位深与调色板；ICC 只读取经过包内路径核对的原字节。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdColors {
    private static final int MAX_PROFILE_BYTES = 1024 * 1024;
    private static final int MAX_PROFILE_LOADS = 32;
    private static final int MAX_TOTAL_PROFILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PROFILE_TAGS = 256;
    private static final int MAX_PALETTE_COLORS = 65_536;
    private static final Definition RGB = new Definition(ColorSpace.TYPE_RGB, 3, 255, List.of(), null);
    private final InvoiceOfdResources resources;
    private final Long defaultId;
    private final Map<Long, Definition> definitions = new HashMap<>();
    private final ProfileBudget profileBudget;

    InvoiceOfdColors(InvoiceOfdResources resources, Long defaultId, ProfileBudget profileBudget) {
        this.resources = resources; this.defaultId = defaultId; this.profileBudget = profileBudget;
    }

    Color read(Element color, Color fallback) throws IOException {
        if (color == null) return fallback;
        shape(color, Set.of("Value", "Index", "ColorSpace", "Alpha"), Set.of());
        Long id = color.hasAttribute("ColorSpace") ? Long.valueOf(id(color, "ColorSpace")) : defaultId;
        Definition definition = id == null ? RGB : definition(id);
        String value = color.hasAttribute("Value") ? color.getAttribute("Value") : null;
        if (value == null && color.hasAttribute("Index")) {
            int index = (int) integer(color.getAttribute("Index"), 0, MAX_PALETTE_COLORS - 1);
            if (index >= definition.palette().size()) throw invalid();
            value = text(definition.palette().get(index));
        }
        float[] channels = new float[definition.components()];
        if (value != null) {
            String[] values = value.trim().split("\\s+");
            if (values.length != channels.length) throw invalid();
            for (int i = 0; i < channels.length; i++) channels[i] = (float) channel(values[i], definition.maximum()) / definition.maximum();
        }
        float[] rgb;
        try {
            if (definition.profile() != null) rgb = definition.profile().toRGB(channels);
            else if (definition.type() == ColorSpace.TYPE_GRAY) rgb = new float[]{channels[0], channels[0], channels[0]};
            else if (definition.type() == ColorSpace.TYPE_CMYK) rgb = PDDeviceCMYK.INSTANCE.toRGB(channels);
            else rgb = channels;
        } catch (RuntimeException failed) { throw invalid(); }
        int alpha = color.hasAttribute("Alpha") ? (int) integer(color.getAttribute("Alpha"), 0, 255) : 255;
        return new Color(component(rgb[0]), component(rgb[1]), component(rgb[2]), alpha);
    }

    private Definition definition(long id) throws IOException {
        Definition cached = definitions.get(id);
        if (cached != null) return cached;
        Element element = resources.element(id, "ColorSpace");
        shape(element, Set.of("ID", "Type", "BitsPerComponent", "Profile"), Set.of("Palette"));
        int type = switch (required(element, "Type")) {
            case "RGB" -> ColorSpace.TYPE_RGB;
            case "GRAY", "Gray" -> ColorSpace.TYPE_GRAY;
            case "CMYK" -> ColorSpace.TYPE_CMYK;
            default -> throw invalid();
        };
        int components = type == ColorSpace.TYPE_GRAY ? 1 : type == ColorSpace.TYPE_RGB ? 3 : 4;
        int bits = element.hasAttribute("BitsPerComponent") ? (int) integer(element.getAttribute("BitsPerComponent"), 1, 16) : 8;
        if (!Set.of(1, 2, 4, 8, 16).contains(bits)) throw invalid();
        Element palette = child(element, "Palette", false);
        List<Element> values = List.of();
        if (palette != null) {
            shape(palette, Set.of(), Set.of("CV")); values = children(palette);
            if (values.isEmpty() || values.size() > MAX_PALETTE_COLORS) throw invalid();
            for (Element value : values) shape(value, Set.of(), Set.of());
        }
        ICC_ColorSpace profile = element.hasAttribute("Profile") ? profile(resources.colorProfile(id), type, components) : null;
        var result = new Definition(type, components, (1 << bits) - 1, values, profile);
        definitions.put(id, result); return result;
    }

    private ICC_ColorSpace profile(byte[] bytes, int type, int components) throws IOException {
        if (bytes.length < 132 || bytes.length > MAX_PROFILE_BYTES) throw invalid();
        profileBudget.consume(bytes.length);
        var input = ByteBuffer.wrap(bytes); long count = Integer.toUnsignedLong(input.getInt(128));
        if (input.getInt(0) != bytes.length || count > MAX_PROFILE_TAGS || 132 + count * 12 > bytes.length) throw invalid();
        for (int i = 0; i < count; i++) {
            long start = Integer.toUnsignedLong(input.getInt(132 + i * 12 + 4));
            long length = Integer.toUnsignedLong(input.getInt(132 + i * 12 + 8));
            if (start + length > bytes.length) throw invalid();
        }
        try {
            var profile = ICC_Profile.getInstance(bytes);
            if (profile.getColorSpaceType() != type || profile.getNumComponents() != components) throw invalid();
            return new ICC_ColorSpace(profile);
        } catch (RuntimeException failed) { throw invalid(); }
    }

    private static int channel(String value, int maximum) throws IOException {
        if (!value.startsWith("#")) return (int) integer(value, 0, maximum);
        if (!value.matches("#[0-9a-fA-F]{1,4}")) throw invalid();
        int channel = Integer.parseInt(value.substring(1), 16);
        if (channel > maximum) throw invalid();
        return channel;
    }

    private static int component(float value) throws IOException {
        if (!Float.isFinite(value)) throw invalid();
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    /**
     * 同一输出页的正文和模板共用预算，资源作用域增加不能放大原生配置加载上限。
     * @author owlzhangfq@gmail.com
     */
    static final class ProfileBudget {
        private int loads;
        private int bytes;

        private void consume(int length) throws IOException {
            if (++loads > MAX_PROFILE_LOADS || length > MAX_TOTAL_PROFILE_BYTES - bytes) throw invalid();
            bytes += length;
        }
    }

    /**
     * 颜色空间只在当前页面资源作用域中缓存，不能跨文档混用同编号资源。
     * @author owlzhangfq@gmail.com
     */
    private record Definition(int type, int components, int maximum, List<Element> palette, ICC_ColorSpace profile) { }
}
