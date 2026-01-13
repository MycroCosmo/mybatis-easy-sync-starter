package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 주의:
 * - 이 클래스는 "개발 환경에서만" 사용하는 것을 전제로 합니다.
 * - 서버 런타임(운영)에서는 src/main/... 경로를 수정할 수 없고, 수정하면 안 됩니다.
 *
 * 권장:
 * - application.yml에서 enabled=true는 개발 환경(profile=local)에서만 켜세요.
 */
public class EntityGenerator {

    private static final Logger log = LoggerFactory.getLogger(EntityGenerator.class);

    // private 타입 필드 추출 (기존 파일 텍스트 분석용)
    private static final Pattern FIELD_PATTERN = Pattern.compile("private\\s+([\\w\\.<>]+)\\s+(\\w+)\\s*;");

    // "Added from DB column" 표시용
    private static final String ADDED_MARK = "// Added from DB column";

    // "DELETED FROM DB" 표시용
    private static final String DELETED_MARK_PREFIX = "// [DELETED FROM DB] Column '";

    // "RENAMED?" 표시용
    private static final String RENAMED_MARK_PREFIX = "// [RENAMED?] This field may have been renamed to column '";

    // "Type Mismatch" 표시용
    private static final String TYPE_MISMATCH_PREFIX = "// [Type Mismatch] DB type:";

    // rename 추정 임계치 (0~1)
    private static final double RENAME_SCORE_THRESHOLD = 0.62;

    /**
     * @param dataSource  DataSource
     * @param basePackage 생성될 VO 패키지 (예: com.foo.app.vo)
     * @param useDbFolder mapper 리소스 경로를 mapper/{dbName}로 둘지 여부
     *
     * 기본 경로:
     * - java: src/main/java/{basePackage}
     * - xml : src/main/resources/mapper[/dbName]
     */
    public void generate(DataSource dataSource, String basePackage, boolean useDbFolder) {
        generate(dataSource, basePackage, useDbFolder,
                Paths.get("src/main/java"),
                Paths.get("src/main/resources/mapper"));
    }

    /**
     * 테스트/확장용 오버로드 (경로를 외부에서 주입 가능)
     */
    public void generate(DataSource dataSource,
                         String basePackage,
                         boolean useDbFolder,
                         Path javaRoot,
                         Path mapperRoot) {

        Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (basePackage == null || basePackage.isBlank()) {
            log.warn("MyBatis-Easy: basePackage is empty. Skip generation.");
            return;
        }

        // src/main/... 경로가 쓰기 가능하지 않으면 중단
        if (!isWritableProjectPath(javaRoot) || !isWritableProjectPath(mapperRoot)) {
            log.warn("MyBatis-Easy: target paths are not writable. " +
                            "This generator is intended for local development only. javaRoot={}, mapperRoot={}",
                    javaRoot.toAbsolutePath(), mapperRoot.toAbsolutePath());
            return;
        }

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            String dbName = safeDbName(meta);
            Path voDir = javaRoot.resolve(basePackage.replace(".", "/"));
            Path xmlDir = useDbFolder ? mapperRoot.resolve(dbName) : mapperRoot;

            ensureDirectory(voDir);
            ensureDirectory(xmlDir);

            try (ResultSet tables = meta.getTables(null, null, null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    if (tableName == null || tableName.isBlank()) continue;

                    String className = convertToPascalCase(tableName);
                    Path javaFilePath = voDir.resolve(className + ".java");

                    // DB 컬럼 정보 수집
                    DbTableSnapshot snapshot = readTableSnapshot(meta, tableName);

                    if (!Files.exists(javaFilePath)) {
                        createFullClass(snapshot, tableName, className, basePackage, javaFilePath);
                    } else {
                        updateExistingClass(snapshot, tableName, className, basePackage, javaFilePath);
                    }

                    // mapper xml 생성(없을 때만)
                    String mapperName = className + "Mapper";
                    Path xmlFilePath = xmlDir.resolve(mapperName + ".xml");
                    if (!Files.exists(xmlFilePath)) {
                        createDefaultXml(basePackage, mapperName, xmlFilePath);
                    }
                }
            }

            log.info("MyBatis-Easy: Entity/XML generation completed. basePackage={}, useDbFolder={}, db={}",
                    basePackage, useDbFolder, dbName);

        } catch (Exception e) {
            log.error("MyBatis-Easy: Generation failed", e);
        }
    }

    // -----------------------------
    // Create class
    // -----------------------------

    private void createFullClass(DbTableSnapshot snapshot,
                                 String tableName,
                                 String className,
                                 String basePackage,
                                 Path javaFilePath) throws Exception {

        String aliasName = decapitalize(className);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("package ").append(basePackage).append(";\n\n")
                .append("import com.thenoah.dev.mybatis_easy_starter.core.annotation.*;\n")
                .append("import org.apache.ibatis.type.Alias;\n")
                .append("import lombok.Data;\n")
                .append("import java.time.*;\n")
                .append("import java.math.*;\n\n")
                .append("@Data\n")
                .append("@Alias(\"").append(aliasName).append("\")\n")
                .append("@Table(name = \"").append(tableName).append("\")\n")
                .append("public class ").append(className).append(" {\n\n");

        // PK 후보 판단: id 컬럼이 있으면 @Id 붙임 (기존 로직 유지)
        for (DbColumn col : snapshot.columns) {
            String colName = col.columnName;
            String fieldName = convertToCamelCase(colName);

            String javaType = fieldName.equalsIgnoreCase("id") ? "Long" : col.javaType;

            if ("id".equalsIgnoreCase(colName)) {
                sb.append("    @Id\n");
            }
            sb.append("    private ").append(javaType).append(" ").append(fieldName).append(";\n\n");
        }

        sb.append("}\n");
        Files.writeString(javaFilePath, sb.toString(), StandardCharsets.UTF_8);
        log.info("MyBatis-Easy: Created [{}]", javaFilePath.getFileName());
    }

    // -----------------------------
    // Update class (sync)
    // -----------------------------

    private void updateExistingClass(DbTableSnapshot snapshot,
                                     String tableName,
                                     String className,
                                     String basePackage,
                                     Path javaFilePath) throws Exception {

        String content = Files.readString(javaFilePath, StandardCharsets.UTF_8);

        // 1) 로컬 파일의 private 필드 파싱 (fieldName -> type)
        Map<String, String> localFieldMap = parseLocalFields(content);

        // 2) DB 필드 정보 (fieldName -> javaType), (fieldName -> columnName)
        LinkedHashSet<String> dbFieldNames = new LinkedHashSet<>();
        Map<String, String> dbFieldTypes = new HashMap<>();
        Map<String, String> dbColumnByFieldName = new HashMap<>();

        for (DbColumn c : snapshot.columns) {
            String fieldName = convertToCamelCase(c.columnName);
            String type = fieldName.equalsIgnoreCase("id") ? "Long" : c.javaType;

            dbFieldNames.add(fieldName);
            dbFieldTypes.put(fieldName, type);
            dbColumnByFieldName.put(fieldName, c.columnName);
        }

        // 3) deletedCandidates / addedCandidates
        // - deleted: local에만 존재
        // - added  : db에만 존재
        Set<String> deletedCandidates = localFieldMap.keySet().stream()
                .filter(f -> !dbFieldNames.contains(f))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> addedCandidates = dbFieldNames.stream()
                .filter(f -> !localFieldMap.containsKey(f))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 3-1) rename 후보 매칭 (deleted <-> added)
        // - 타입 동일 우선
        // - 이름 유사도 기반 점수
        Map<String, String> renameDeletedToAdded = detectRenamePairs(
                deletedCandidates,
                addedCandidates,
                localFieldMap,
                dbFieldTypes
        );

        // rename으로 매칭된 added는 "신규 컬럼 추가"로 취급하지 않음(중복/혼란 방지)
        Set<String> matchedAdded = new HashSet<>(renameDeletedToAdded.values());
        Set<String> effectiveAdded = addedCandidates.stream()
                .filter(a -> !matchedAdded.contains(a))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean changed = false;

        // 4) 삭제/rename 주석 마킹
        for (String fieldName : deletedCandidates) {
            String fieldType = localFieldMap.get(fieldName);
            if (fieldType == null) continue;

            String targetLine = "private " + fieldType + " " + fieldName + ";";
            if (!content.contains(targetLine)) continue;

            // rename 후보면 RENAMED? 주석만 (DELETED 주석 대신)
            if (renameDeletedToAdded.containsKey(fieldName)) {
                String addedField = renameDeletedToAdded.get(fieldName);
                String newColumn = dbColumnByFieldName.getOrDefault(addedField, camelToSnake(addedField));
                String renameMsg = RENAMED_MARK_PREFIX + newColumn + "' (field: " + addedField + ")";

                if (!content.contains(renameMsg)) {
                    content = content.replace(targetLine, renameMsg + "\n    " + targetLine);
                    changed = true;
                    log.warn("MyBatis-Easy: Field [{}] in [{}] marked as RENAMED? -> {}",
                            fieldName, className, newColumn);
                }
                continue;
            }

            // rename 후보가 아니면 기존처럼 DELETED 주석
            String deletedMsg = DELETED_MARK_PREFIX + camelToSnake(fieldName) + "' no longer exists in table " + tableName;
            if (!content.contains(deletedMsg)) {
                content = content.replace(targetLine, deletedMsg + "\n    " + targetLine);
                changed = true;
                log.warn("MyBatis-Easy: Field [{}] in [{}] marked as DELETED (missing in DB).", fieldName, className);
            }
        }

        // 5) 신규 필드 추가(rename으로 추정된 건 제외) + 타입 mismatch 체크
        StringBuilder newFields = new StringBuilder();

        // 신규 필드
        for (String dbFieldName : effectiveAdded) {
            String dbJavaType = dbFieldTypes.get(dbFieldName);
            if (dbJavaType == null) dbJavaType = "String";

            newFields.append("    private ").append(dbJavaType).append(" ").append(dbFieldName).append("; ")
                    .append(ADDED_MARK).append("\n\n");
            changed = true;
        }

        // 타입 mismatch (DB에 존재하는 필드들만)
        for (String dbFieldName : dbFieldNames) {
            if (!localFieldMap.containsKey(dbFieldName)) continue;

            String existingType = localFieldMap.get(dbFieldName);
            String dbJavaType = dbFieldTypes.get(dbFieldName);

            if (existingType != null && dbJavaType != null && !existingType.equals(dbJavaType)) {
                String typeWarning = TYPE_MISMATCH_PREFIX + " " + dbJavaType + " (Current: " + existingType + ")";
                String targetLine = "private " + existingType + " " + dbFieldName + ";";

                if (content.contains(targetLine) && !content.contains(typeWarning)) {
                    content = content.replace(targetLine, typeWarning + "\n    " + targetLine);
                    changed = true;
                }
            }
        }

        if (!changed) {
            return;
        }

        // 6) 클래스 끝(마지막 }) 직전에 신규 필드 삽입
        int lastBrace = content.lastIndexOf('}');
        if (lastBrace < 0) {
            log.warn("MyBatis-Easy: Invalid java file structure. skip update. file={}", javaFilePath);
            return;
        }

        String before = content.substring(0, lastBrace).trim();
        String updated = before
                + "\n\n"
                + newFields
                + "}\n";

        Files.writeString(javaFilePath, updated, StandardCharsets.UTF_8);
        log.info("MyBatis-Easy: Synced [{}] (Added/TypeCheck/DeletedCheck/RenameHint)", javaFilePath.getFileName());
    }

    /**
     * rename 후보 매칭:
     * - deletedCandidates: VO에만 있는 필드명
     * - addedCandidates  : DB에만 있는 필드명
     * - localFieldMap    : VO field -> type
     * - dbFieldTypes     : DB field -> type
     *
     * 결과: deletedField -> addedField (1:1 매칭)
     */
    private Map<String, String> detectRenamePairs(Set<String> deletedCandidates,
                                                  Set<String> addedCandidates,
                                                  Map<String, String> localFieldMap,
                                                  Map<String, String> dbFieldTypes) {

        // deleted마다 best added 후보를 찾고, added도 중복 매칭 방지(1:1)
        Map<String, String> result = new HashMap<>();
        Set<String> usedAdded = new HashSet<>();

        for (String deleted : deletedCandidates) {
            String deletedType = localFieldMap.get(deleted);
            if (deletedType == null) continue;

            String bestAdded = null;
            double bestScore = 0.0;

            for (String added : addedCandidates) {
                if (usedAdded.contains(added)) continue;

                String addedType = dbFieldTypes.get(added);
                if (addedType == null) continue;

                // 타입이 다르면 rename으로 보기 어렵다(보수적으로)
                if (!deletedType.equals(addedType)) continue;

                double score = similarityScore(deleted, added);
                if (score > bestScore) {
                    bestScore = score;
                    bestAdded = added;
                }
            }

            if (bestAdded != null && bestScore >= RENAME_SCORE_THRESHOLD) {
                result.put(deleted, bestAdded);
                usedAdded.add(bestAdded);
            }
        }

        return result;
    }

    /**
     * 이름 유사도 점수 (0~1)
     * - camel/snake 혼합을 고려해서 정규화 후 비교
     */
    private double similarityScore(String a, String b) {
        String na = normalizeName(a);
        String nb = normalizeName(b);

        if (na.equals(nb)) return 1.0;
        if (na.contains(nb) || nb.contains(na)) return 0.92;

        int dist = levenshteinDistance(na, nb);
        int max = Math.max(na.length(), nb.length());
        if (max == 0) return 0.0;

        double ratio = 1.0 - ((double) dist / (double) max);

        // 너무 짧은 단어는 오탐이 많아서 약간 페널티
        if (max <= 4) ratio -= 0.08;

        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private String normalizeName(String s) {
        if (s == null) return "";
        // camelCase -> snake -> remove underscores
        String snake = camelToSnake(s);
        return snake.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private int levenshteinDistance(String s1, String s2) {
        int n = s1.length();
        int m = s2.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];

        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char c1 = s1.charAt(i - 1);

            for (int j = 1; j <= m; j++) {
                char c2 = s2.charAt(j - 1);
                int cost = (c1 == c2) ? 0 : 1;

                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }

            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[m];
    }

    private Map<String, String> parseLocalFields(String content) {
        Map<String, String> localFieldMap = new LinkedHashMap<>();
        Matcher m = FIELD_PATTERN.matcher(content);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            localFieldMap.put(name, type);
        }
        return localFieldMap;
    }

    // -----------------------------
    // Create default XML
    // -----------------------------

    private void createDefaultXml(String basePackage, String mapperName, Path xmlFilePath) throws Exception {
        String rootPackage = basePackage.contains(".")
                ? basePackage.substring(0, basePackage.lastIndexOf('.'))
                : basePackage;

        String namespace = rootPackage + ".mapper." + mapperName;

        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">

                </mapper>
                """.formatted(namespace);

        Files.writeString(xmlFilePath, xml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        log.info("MyBatis-Easy: Created mapper xml [{}]", xmlFilePath.getFileName());
    }

    // -----------------------------
    // DB snapshot
    // -----------------------------

    private DbTableSnapshot readTableSnapshot(DatabaseMetaData meta, String tableName) throws Exception {
        List<DbColumn> columns = new ArrayList<>();

        try (ResultSet cols = meta.getColumns(null, null, tableName, null)) {
            while (cols.next()) {
                String colName = cols.getString("COLUMN_NAME");
                int jdbcType = cols.getInt("DATA_TYPE");
                String javaType = mapSqlTypeToJavaType(jdbcType);
                columns.add(new DbColumn(colName, jdbcType, javaType));
            }
        }

        return new DbTableSnapshot(tableName, columns);
    }

    private String safeDbName(DatabaseMetaData meta) {
        try {
            return meta.getDatabaseProductName().toLowerCase().replaceAll("\\s", "");
        } catch (Exception e) {
            return "db";
        }
    }

    private void ensureDirectory(Path path) throws Exception {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private boolean isWritableProjectPath(Path root) {
        try {
            Path abs = root.toAbsolutePath();
            if (!Files.exists(abs)) {
                Files.createDirectories(abs);
            }
            Path probe = abs.resolve(".mybatis-easy-probe-" + System.nanoTime());
            Files.writeString(probe, "probe", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------
    // Type mapping
    // -----------------------------

    private String mapSqlTypeToJavaType(int type) {
        return switch (type) {
            case Types.BIGINT -> "Long";
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "Integer";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "LocalDateTime";
            case Types.DATE -> "LocalDate";
            case Types.BOOLEAN, Types.BIT -> "Boolean";
            case Types.DOUBLE, Types.FLOAT -> "Double";
            case Types.DECIMAL, Types.NUMERIC -> "BigDecimal";
            default -> "String";
        };
    }

    // -----------------------------
    // Naming utils
    // -----------------------------

    private String convertToPascalCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Arrays.stream(s.split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase())
                .collect(Collectors.joining());
    }

    private String convertToCamelCase(String s) {
        String p = convertToPascalCase(s);
        return (p == null || p.isEmpty()) ? p : decapitalize(p);
    }

    private String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    private String camelToSnake(String str) {
        return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
    }

    // -----------------------------
    // Internal models
    // -----------------------------

    private static class DbTableSnapshot {
        final String tableName;
        final List<DbColumn> columns;

        private DbTableSnapshot(String tableName, List<DbColumn> columns) {
            this.tableName = tableName;
            this.columns = columns;
        }
    }

    private static class DbColumn {
        final String columnName;
        final int jdbcType;
        final String javaType;

        private DbColumn(String columnName, int jdbcType, String javaType) {
            this.columnName = columnName;
            this.jdbcType = jdbcType;
            this.javaType = javaType;
        }
    }
}
