package com.thenoah.dev.mybatis_easy_processor.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class XmlParser {

    private XmlParser() {}

    public static ParsedXml parse(Path xmlPath) throws Exception {
        Document doc = parseDom(xmlPath);

        Element mapper = (Element) doc.getElementsByTagName("mapper").item(0);
        if (mapper == null) {
            return new ParsedXml(null, Set.of());
        }

        String namespace = mapper.getAttribute("namespace");

        Set<String> ids = new LinkedHashSet<>();
        collectIds(mapper, "select", ids);
        collectIds(mapper, "insert", ids);
        collectIds(mapper, "update", ids);
        collectIds(mapper, "delete", ids);

        return new ParsedXml(namespace, ids);
    }

    private static void collectIds(Element mapper, String tag, Set<String> out) {
        NodeList list = mapper.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Element el = (Element) list.item(i);
            String id = el.getAttribute("id");
            if (id != null && !id.isBlank()) out.add(id);
        }
    }

    private static Document parseDom(Path xmlPath) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

        // ✅ 보안/안정: 외부 DTD/외부 엔티티 로딩 차단 (DOCTYPE 있는 MyBatis XML 파싱이 여기서 많이 터집니다)
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setNamespaceAware(false);

        DocumentBuilder builder = dbf.newDocumentBuilder();

        try (InputStream is = Files.newInputStream(xmlPath)) {
            return builder.parse(is);
        }
    }

    public record ParsedXml(String namespace, Set<String> statementIds) {}
}
