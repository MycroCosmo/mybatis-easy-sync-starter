package com.thenoah.dev.mybatis_easy_processor.generate;

import com.thenoah.dev.mybatis_easy_processor.model.DiffResult;
import com.thenoah.dev.mybatis_easy_processor.scan.XmlMapperScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class XmlStubGenerator {

    public XmlStubGenerator() {}

    public void generateMissingStubs(DiffResult diff, XmlMapperScanner.XmlIndex xmlIndex) throws Exception {
        if (diff == null || xmlIndex == null) return;

        Map<String, Set<String>> missingMap = nullToEmpty(diff.missing());
        Map<String, Set<String>> orphanMap  = nullToEmpty(diff.orphan());

        // missing 또는 orphan이 있는 namespace 전체
        Set<String> namespaces = new LinkedHashSet<>();
        namespaces.addAll(missingMap.keySet());
        namespaces.addAll(orphanMap.keySet());

        if (namespaces.isEmpty()) return;

        // 재현성: 처리 순서 고정
        List<String> ordered = new ArrayList<>(namespaces);
        Collections.sort(ordered);

        for (String namespace : ordered) {
            Set<String> missingIds = missingMap.getOrDefault(namespace, Set.of());
            Set<String> orphanIds  = orphanMap.getOrDefault(namespace, Set.of());

            if (missingIds.isEmpty() && orphanIds.isEmpty()) continue;

            Path targetXml = xmlIndex.xmlPathOf(namespace)
                    .orElseGet(() -> defaultXmlPath(namespace, xmlIndex));

            Path parent = targetXml.getParent();

            // 파일이 없으면: missing 있을 때만 새 파일 생성
            if (!Files.exists(targetXml)) {
                if (!missingIds.isEmpty()) {
                    if (parent != null) Files.createDirectories(parent);
                    createNewMapperXml(targetXml, namespace, missingIds);
                }
                continue;
            }

            // 파일이 있으면: 섹션 보장 후 필요한 작업만
            if (parent != null) Files.createDirectories(parent);

            SafeXmlWriter.ensureMesSectionExists(targetXml);

            if (!missingIds.isEmpty()) {
                SafeXmlWriter.appendMissingStatements(targetXml, missingIds);
            }
            if (!orphanIds.isEmpty()) {
                SafeXmlWriter.markOrphansWithComment(targetXml, orphanIds);
            }
        }
    }

    /**
     * 새 파일 생성 경로는 XmlMapperScanner가 스캔한 "resolvedRoot" 기준
     * - 스캔/생성 기준이 동일
     * - flat-only 정책에 맞춰 단순 파일명 생성
     */
    private Path defaultXmlPath(String namespace, XmlMapperScanner.XmlIndex xmlIndex) {
        int lastDot = namespace.lastIndexOf('.');
        String simple = (lastDot > 0) ? namespace.substring(lastDot + 1) : namespace;

        Path dir = xmlIndex.resolvedRoot();
        return dir.resolve(simple + ".xml");
    }

    private void createNewMapperXml(Path xmlPath, String namespace, Set<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return;

        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);

        List<String> blocks = new ArrayList<>(sorted.size());
        for (String id : sorted) {
            blocks.add(buildAutoGenBlockForNewFile(id));
        }
        String sectionBody = String.join("\n\n", blocks);

        StringBuilder sb = new StringBuilder(512 + sectionBody.length());
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE mapper\n");
        sb.append("  PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\"\n");
        sb.append("  \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n");
        sb.append("<mapper namespace=\"").append(namespace).append("\">\n");
        sb.append("\t<!-- MES-AUTO-GENERATED:SECTION-BEGIN -->\n");
        sb.append(sectionBody).append("\n");
        sb.append("\t<!-- MES-AUTO-GENERATED:SECTION-END -->\n");
        sb.append("</mapper>\n");

        SafeXmlWriter.atomicWriteNew(xmlPath, sb.toString());
    }

    private String buildAutoGenBlockForNewFile(String id) {
        String tag = guessTagById(id);

        return "\t<" + tag + " id=\"" + id + "\">\n"
             + "\t  /* TODO: write SQL */\n"
             + "\t</" + tag + ">";
    }

    private String guessTagById(String id) {
        String lower = id.toLowerCase(Locale.ROOT);

        if (lower.startsWith("insert") || lower.startsWith("save") || lower.startsWith("create")) return "insert";
        if (lower.startsWith("update") || lower.startsWith("modify")) return "update";
        if (lower.startsWith("delete") || lower.startsWith("remove")) return "delete";
        return "select";
    }

    private static <K, V> Map<K, V> nullToEmpty(Map<K, V> m) {
        return (m == null) ? Map.of() : m;
    }
}
