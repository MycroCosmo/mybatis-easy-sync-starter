package com.thenoah.dev.mybatis_easy_processor.scan;

import com.thenoah.dev.mybatis_easy_processor.config.ProcessorOptions;
import com.thenoah.dev.mybatis_easy_processor.util.XmlParser;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class XmlMapperScanner {

    private final ProcessorOptions options;

    public XmlMapperScanner(ProcessorOptions options) {
        this.options = options;
    }

    public XmlIndex scan() throws Exception {
        Path root = resolveXmlRootCached(options.xmlDir());

        Map<String, Path> nsToPath = new HashMap<>();
        Map<String, Set<String>> nsToIds = new HashMap<>();

        if (!Files.isDirectory(root)) {
            return new XmlIndex(root, nsToPath, nsToIds);
        }

        // flat-only 정책: xmlDir 바로 아래 *.xml만 읽는다.
        List<Path> xmlFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.xml")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) xmlFiles.add(p);
            }
        }

        // 재현성: 파일명 기준 정렬
        xmlFiles.sort(Comparator.comparing(Path::getFileName));

        for (Path p : xmlFiles) {
            final XmlParser.ParsedXml parsed;
            try {
                parsed = XmlParser.parse(p);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "MES failed to parse mapper xml: " + p.toAbsolutePath() +
                        " (" + e.getClass().getSimpleName() + ": " + safeMsg(e) + ")",
                        e
                );
            }

            String ns = parsed.namespace();
            if (ns == null || ns.isBlank()) continue;

            Path prev = nsToPath.putIfAbsent(ns, p);
            if (prev != null) {
                throw new IllegalStateException(
                        "MES duplicate mapper namespace detected: " + ns + "\n" +
                        "- first(abs): " + prev.toAbsolutePath() + "\n" +
                        "- second(abs): " + p.toAbsolutePath()
                );
            }

            // 재현성: ids 정렬 고정 + 불변
            Set<String> ids = parsed.statementIds();
            Set<String> sorted = (ids == null || ids.isEmpty())
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(ids));

            nsToIds.put(ns, sorted);
        }

        return new XmlIndex(root, nsToPath, nsToIds);
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? "(no message)" : m;
    }

    // ===================== root resolve caching =====================

    /**
     * 캐시 키: (user.dir | xmlDirRaw)
     * - IDE/Gradle에서 반복 컴파일 시 resolve 비용을 크게 줄여줌
     */
    private static final Map<String, Path> RESOLVED_ROOT_CACHE = new ConcurrentHashMap<>();

    private static Path resolveXmlRootCached(String xmlDirRaw) {
        String userDir = System.getProperty("user.dir");
        String key = userDir + "|" + xmlDirRaw;
        return RESOLVED_ROOT_CACHE.computeIfAbsent(key, k -> resolveXmlRoot(xmlDirRaw));
    }

    /**
     * ✅ 상대경로 xmlDir을 “프로젝트 루트” 기준으로 고정해서 해석
     * - user.dir에서 위로 올라가며 settings.gradle(.kts) / build.gradle(.kts) / pom.xml을 찾음
     * - 찾으면 그 디렉터리를 루트로 확정하고 xmlDir을 resolve
     * - 못 찾으면 user.dir 기준(최후의 수단)
     */
    private static Path resolveXmlRoot(String xmlDirRaw) {
        Path xmlDirPath = Path.of(xmlDirRaw);
        if (xmlDirPath.isAbsolute()) return xmlDirPath.normalize();

        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path projectRoot = findProjectRootCached(start).orElse(start);

        return projectRoot.resolve(xmlDirPath).normalize();
    }

    // project root 탐색 캐시: start 디렉터리가 같으면 결과도 같다고 가정(현실적으로 맞음)
    private static final Map<Path, Optional<Path>> PROJECT_ROOT_CACHE = new ConcurrentHashMap<>();

    private static Optional<Path> findProjectRootCached(Path start) {
        return PROJECT_ROOT_CACHE.computeIfAbsent(start, XmlMapperScanner::findProjectRoot);
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
        private final Path resolvedRoot;
        private final Map<String, Path> nsToPath;
        private final Map<String, Set<String>> nsToIds;

        public XmlIndex(Path resolvedRoot, Map<String, Path> nsToPath, Map<String, Set<String>> nsToIds) {
            this.resolvedRoot = resolvedRoot;

            // 외부에서 실수로 수정 못 하게 불변으로 감쌈
            this.nsToPath = Collections.unmodifiableMap(new LinkedHashMap<>(nsToPath));
            this.nsToIds  = Collections.unmodifiableMap(new LinkedHashMap<>(nsToIds));
        }

        /** XmlMapperScanner가 실제로 스캔한 xmlDir의 "resolved absolute root" */
        public Path resolvedRoot() {
            return resolvedRoot;
        }

        public Optional<Path> xmlPathOf(String namespace) {
            return Optional.ofNullable(nsToPath.get(namespace));
        }

        public Set<String> idsOf(String namespace) {
            return nsToIds.getOrDefault(namespace, Set.of());
        }

        public Set<String> namespaces() {
            return nsToPath.keySet(); // 이미 unmodifiableMap
        }
    }
}
