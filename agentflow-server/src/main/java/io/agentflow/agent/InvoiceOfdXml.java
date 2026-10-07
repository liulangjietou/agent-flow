package io.agentflow.agent;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 绘制入口的 XML 形状和标量读取；未知绘制属性不能悄悄被忽略。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdXml {
    private static final Set<String> NAMESPACES = Set.of("http://www.ofdspec.org/2016", "http://www.ofdspec.org");
    private static final double MAX_NUMBER = 1_000_000;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private InvoiceOfdXml() { }

    /** 兼容已见旧票面的命名空间；同一 XML 的版本一致性由解析入口一次检查。 */
    static boolean ofdNamespace(String namespace) { return namespace != null && NAMESPACES.contains(namespace); }

    static List<Element> children(Element parent) throws IOException {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (!ofdNamespace(element.getNamespaceURI()) || !element.getNamespaceURI().equals(parent.getNamespaceURI())) throw invalid();
                result.add(element);
            }
        }
        return result;
    }

    static List<Element> children(Element parent, String name) throws IOException {
        return children(parent).stream().filter(element -> name.equals(element.getLocalName())).toList();
    }

    static Element child(Element parent, String name, boolean required) throws IOException {
        var matches = children(parent, name);
        if (matches.size() > 1 || required && matches.isEmpty()) throw invalid();
        return matches.isEmpty() ? null : matches.get(0);
    }

    static void shape(Element element, Set<String> attributes, Set<String> children) throws IOException {
        var attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var attribute = attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) continue;
            if (XMLConstants.XML_NS_URI.equals(attribute.getNamespaceURI()) && attribute.getLocalName().equals("space")) continue;
            if (attribute.getNamespaceURI() != null || !attributes.contains(attribute.getNodeName())) throw invalid();
        }
        for (Element child : children(element)) if (!children.contains(child.getLocalName())) throw invalid();
    }

    static String text(Element element) throws IOException {
        if (!children(element).isEmpty()) throw invalid();
        return element.getTextContent();
    }

    static String required(Element element, String attribute) throws IOException {
        String value = element.getAttribute(attribute);
        if (value.isBlank()) throw invalid();
        return value;
    }

    static double number(String token) throws IOException {
        if (token.length() > 64 || !DECIMAL.matcher(token).matches()) throw invalid();
        double result = Double.parseDouble(token);
        bounded(result);
        return result;
    }

    static double number(Element element, String attribute, double defaultValue) throws IOException {
        return element.hasAttribute(attribute) ? number(element.getAttribute(attribute)) : defaultValue;
    }

    static void bounded(double value) throws IOException {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_NUMBER) throw invalid();
    }

    static long integer(String token, long min, long max) throws IOException {
        if (!token.matches("\\+?[0-9]{1,10}")) throw invalid();
        long value = Long.parseLong(token);
        if (value < min || value > max) throw invalid();
        return value;
    }

    static long id(Element element, String attribute) throws IOException { return integer(required(element, attribute), 1, 0xffff_ffffL); }

    static double[] numbers(String text, int count) throws IOException {
        String[] tokens = text.trim().split("\\s+");
        if (tokens.length != count) throw invalid();
        var result = new double[count];
        for (int i = 0; i < count; i++) result[i] = number(tokens[i]);
        return result;
    }

    static boolean bool(Element element, String attribute, boolean defaultValue) throws IOException {
        if (!element.hasAttribute(attribute)) return defaultValue;
        return switch (element.getAttribute(attribute)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw invalid();
        };
    }

    static AffineTransform transform(Element element) throws IOException {
        return element.hasAttribute("CTM") ? new AffineTransform(numbers(element.getAttribute("CTM"), 6)) : new AffineTransform();
    }

    static String parent(String file) { int split = file.lastIndexOf('/'); return split < 0 ? "" : file.substring(0, split); }
    static IOException invalid() { return new IOException("OFD drawing data is invalid, unsupported or exceeds rendering limits"); }
}
