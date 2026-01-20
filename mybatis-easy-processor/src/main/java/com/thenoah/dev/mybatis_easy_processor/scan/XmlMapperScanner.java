package com.thenoah.dev.mybatis_easy_processor.scan;

import com.thenoah.dev.mybatis_easy_processor.config.ProcessorOptions;
import com.thenoah.dev.mybatis_easy_processor.util.XmlParser;

import java.nio.file.*;
import java.util.*;

public class XmlMapperScanner {

    private final ProcessorOptions options;

    public XmlMapperScanner(ProcessorOptions options) {
        this.options = options;
    }

    public XmlIndex scan() throws Exception {
        Path root = resolveXmlRoot(options.xmlDir());

        // ✅ 디버그(원인 확정용): user.dir 흔들림 방지 확인
        System.err.println("MES DEBUG user.dir=" + System.getProperty("user.dir"));
        System.err.println("MES DEBUG xmlDir(raw)=" + options.xmlDir());
        System.err.println("MES DEBUG xmlDir(resolved)=" + root);

        Map<String, Path> nsToPath = new HashMap<>();
        Map<String, Set<String>> nsToIds = new HashMap<>();

        if (!Files.exists(root)) return new XmlIndex(nsToPath, nsToIds);

        try (var stream = Files.walk(root)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".xml"))
                  .forEach(p -> {
                      try {
                          // ✅ flat-only 강제: xmlDir 바로 아래 파일만 허용
                          Path rel = root.relativize(p);
                          if (rel.getNameCount() != 1) {
                              throw new IllegalStateException(
                                      "MES policy violation: mapper xml must be directly under xmlDir (flat).\n" +
                                      "- xmlDir(resolved): " + root + "\n" +
                                      "- found(abs): " + p.toAbsolutePath() + "\n" +
                                      "- rel: " + rel
                              );
                          }

                          XmlParser.ParsedXml parsed = XmlParser.parse(p);
                          String ns = parsed.namespace();
                          if (ns == null || ns.isBlank()) return;

                          // ✅ 동일 namespace 중복 방지
                          Path prev = nsToPath.putIfAbsent(ns, p);
                          if (prev != null) {
                              throw new IllegalStateException(
                                      "MES duplicate mapper namespace detected: " + ns + "\n" +
                                      "- first(abs): " + prev.toAbsolutePath() + "\n" +
                                      "- second(abs): " + p.toAbsolutePath()
                              );
                          }

                          nsToIds.put(ns, parsed.statementIds());
                      } catch (Exception ex) {
                          throw new RuntimeException(ex);
                      }
                  });
        } catch (RuntimeException re) {
            if (re.getCause() instanceof Exception e) throw e;
            throw re;
        }

        return new XmlIndex(nsToPath, nsToIds);
    }

    /**
     * ✅ 상대경로 xmlDir을 “프로젝트 루트” 기준으로 고정해서 해석
     * - 우선 user.dir에서 위로 올라가며 settings.gradle(.kts) / build.gradle(.kts) / pom.xml을 찾음
     * - 찾으면 그 디렉터리를 루트로 확정하고 xmlDir을 resolve
     * - 못 찾으면 기존대로 user.dir 기준(최후의 수단)
     */
    private static Path resolveXmlRoot(String xmlDirRaw) {
        Path xmlDirPath = Path.of(xmlDirRaw);
        if (xmlDirPath.isAbsolute()) {
            return xmlDirPath.normalize();
        }

        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(start).orElse(start);

        return projectRoot.resolve(xmlDirPath).normalize();
    }

    private static Optional<Path> findProjectRoot(Path start) {
        Path cur = start;
        while (cur != null) {
            if (Files.exists(cur.resolve("settings.gradle")) ||
                Files.exists(cur.resolve("settings.gradle.kts")) ||
                Files.exists(cur.resolve("build.gradle")) ||
                Files.exists(cur.resolve("build.gradle.kts")) ||
                Files.exists(cur.resolve("pom.xml"))) {
                return Optional.of(cur);
            }
            cur = cur.getParent();
        }
        return Optional.empty();
    }

    public static class XmlIndex {
        private final Map<String, Path> nsToPath;
        private final Map<String, Set<String>> nsToIds;

        public XmlIndex(Map<String, Path> nsToPath, Map<String, Set<String>> nsToIds) {
            this.nsToPath = nsToPath;
            this.nsToIds = nsToIds;
        }

        public Optional<Path> xmlPathOf(String namespace) {
            return Optional.ofNullable(nsToPath.get(namespace));
        }

        public Set<String> idsOf(String namespace) {
            return nsToIds.getOrDefault(namespace, Set.of());
        }

        public Set<String> namespaces() {
            return nsToPath.keySet();
        }
    }
}
