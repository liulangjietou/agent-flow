package io.agentflow.definition;

import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;

/**
 * 流程、节点和连线保留原标识，字符规则采用标准 NCName，避免自行维护 Unicode 范围。
 * @author owlzhangfq@gmail.com
 */
final class DefinitionIdentifiers {
    private static final String ELEMENT = "identifier";
    private static final Schema SCHEMA = schema();

    private DefinitionIdentifiers() { }

    /** 只发送文本事件，不把标识当 XML 解析；独立校验器不跨线程共享可变状态。 */
    static boolean valid(String value) {
        // NCName 的空白折叠不能改变业务标识，原文有空白就要求设计者修正。
        if (value.chars().anyMatch(character -> character == ' ' || character == '\t' || character == '\r' || character == '\n')) return false;
        var validator = SCHEMA.newValidatorHandler();
        try {
            validator.startDocument();
            validator.startElement("", ELEMENT, ELEMENT, new AttributesImpl());
            char[] characters = value.toCharArray();
            validator.characters(characters, 0, characters.length);
            validator.endElement("", ELEMENT, ELEMENT);
            validator.endDocument();
            return true;
        } catch (SAXException invalid) {
            return false;
        }
    }

    private static Schema schema() {
        try {
            var factory = SchemaFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newSchema(new StreamSource(new StringReader("""
                    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                      <xs:element name="identifier" type="xs:NCName"/>
                    </xs:schema>
                    """)));
        } catch (SAXException invalidSchema) {
            throw new IllegalStateException("Unable to initialize definition identifier validation", invalidSchema);
        }
    }
}
