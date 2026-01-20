package com.thenoah.dev.mybatis_easy_processor.util;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class XmlParser {

    private XmlParser() {}

    /**
     * XMLInputFactory는 생성 비용이 있고, 구현체에 따라 내부 초기화가 큼.
     * - ThreadLocal로 캐시하면 반복 컴파일/다수 XML에서 체감 개선.
     * - 프로세서 환경은 보통 단일 스레드지만, 안전하게 ThreadLocal 사용.
     */
    private static final ThreadLocal<XMLInputFactory> FACTORY = ThreadLocal.withInitial(() -> {
        XMLInputFactory f = XMLInputFactory.newFactory();

        // DTD/외부 엔티티 차단 (MyBatis DOCTYPE 대응)
        safeSet(f, XMLInputFactory.SUPPORT_DTD, false);
        safeSet(f, "javax.xml.stream.isSupportingExternalEntities", false);
        safeSet(f, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);

        // 일부 구현체에서 성능/안정성에 도움될 수 있는 옵션(미지원이면 무시)
        safeSet(f, XMLInputFactory.IS_NAMESPACE_AWARE, false);
        safeSet(f, XMLInputFactory.IS_COALESCING, false);

        return f;
    });

    public static ParsedXml parse(Path xmlPath) throws Exception {
        String namespace = null;
        Set<String> ids = new LinkedHashSet<>();

        XMLStreamReader r = null;
        try (InputStream is = Files.newInputStream(xmlPath)) {
            r = FACTORY.get().createXMLStreamReader(is);

            while (r.hasNext()) {
                int t = r.next();
                if (t != XMLStreamConstants.START_ELEMENT) continue;

                String name = r.getLocalName();

                if ("mapper".equals(name)) {
                    // StAX 표준 API로 바로 조회 (루프 제거)
                    namespace = r.getAttributeValue(null, "namespace");
                    continue;
                }

                // statement 4종만 수집
                if (isStatementTag(name)) {
                    String id = r.getAttributeValue(null, "id");
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }

            return new ParsedXml(namespace, freeze(ids));
        } catch (Exception e) {
            // 상위에서 파일 경로와 함께 wrapping 하므로 여기서는 그대로 throw해도 되지만,
            // parse 단위에서도 메시지 품질을 올려두면 디버깅이 쉬움.
            throw new IllegalStateException(
                    "MES XmlParser failed: " + xmlPath.toAbsolutePath() +
                    " (" + e.getClass().getSimpleName() + ": " + safeMsg(e) + ")",
                    e
            );
        } finally {
            if (r != null) {
                try { r.close(); } catch (Exception ignore) {}
            }
        }
    }

    private static boolean isStatementTag(String name) {
        // 분기 예측/문자열 비교 비용 최소화용 switch
        return switch (name) {
            case "select", "insert", "update", "delete" -> true;
            default -> false;
        };
    }

    private static Set<String> freeze(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(ids);
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? "(no message)" : m;
    }

    private static void safeSet(XMLInputFactory f, String key, Object value) {
        try {
            f.setProperty(key, value);
        } catch (IllegalArgumentException ignore) {
            // 구현체별 미지원 property가 있을 수 있음
        }
    }

    public record ParsedXml(String namespace, Set<String> statementIds) {}
}
