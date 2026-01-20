package com.thenoah.dev.mybatis_easy_processor.generate;

import com.thenoah.dev.mybatis_easy_processor.config.ProcessorOptions;
import com.thenoah.dev.mybatis_easy_processor.model.DiffResult;
import com.thenoah.dev.mybatis_easy_processor.scan.XmlMapperScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class XmlStubGenerator {

    private final ProcessorOptions options;

    public XmlStubGenerator(ProcessorOptions options) {
        this.options = options;
    }

    public void generateMissingStubs(DiffResult diff, XmlMapperScanner.XmlIndex xmlIndex) throws Exception {
        System.out.println("MES: generateMissingStubs called");
        System.out.println("MES: xmlDir = " + options.xmlDir());

        // missing 또는 orphan이 있는 namespace 전체를 처리
        Set<String> namespaces = new LinkedHashSet<>();
        namespaces.addAll(diff.missing().keySet());
        namespaces.addAll(diff.orphan().keySet());

        System.out.println("MES: namespaces to process=" + namespaces);

        for (String namespace : namespaces) {
            Set<String> missingIds = diff.missing().getOrDefault(namespace, Set.of());
            Set<String> orphanIds  = diff.orphan().getOrDefault(namespace, Set.of());

            Path targetXml = xmlIndex.xmlPathOf(namespace)
                .orElseGet(() -> defaultXmlPath(namespace));

            Files.createDirectories(targetXml.getParent());

            System.out.println("MES: namespace=" + namespace);
            System.out.println("MES: target xml=" + targetXml.toAbsolutePath());
            System.out.println("MES: missing ids=" + missingIds);
            System.out.println("MES: orphan ids=" + orphanIds);

            if (!Files.exists(targetXml)) {
                // 새 파일 생성은 missing이 있을 때만 의미 있음
                if (!missingIds.isEmpty()) {
                    createNewMapperXml(targetXml, namespace, missingIds);
                }
                // orphan만 있고 파일이 없으면 할 일 없음
                continue;
            }

            SafeXmlWriter.ensureMesSectionExists(targetXml);

            SafeXmlWriter.appendMissingStatements(targetXml, missingIds);

            SafeXmlWriter.markOrphansWithComment(targetXml, orphanIds);
        }
    }

    private Path defaultXmlPath(String namespace) {
        int lastDot = namespace.lastIndexOf('.');
        String simple = (lastDot > 0) ? namespace.substring(lastDot + 1) : namespace;

        Path dir = Path.of(options.xmlDir());
        return dir.resolve(simple + ".xml");
    }

    private void createNewMapperXml(Path xmlPath, String namespace, Set<String> ids) throws Exception {
        Files.createDirectories(xmlPath.getParent());

        List<String> blocks = new ArrayList<>();
        for (String id : ids) {
            blocks.add(buildAutoGenBlockForNewFile(id));
        }
        String sectionBody = String.join("\n\n", blocks);

        StringBuilder sb = new StringBuilder();
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

    // 새 파일 생성 시에는 이미 섹션 안이므로 탭을 포함한 블록을 생성
    private String buildAutoGenBlockForNewFile(String id) {
        String tag = guessTagById(id);

        return ""
            + "\t<" + tag + " id=\"" + id + "\">\n"
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
}
