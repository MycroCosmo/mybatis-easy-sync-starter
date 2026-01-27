package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityGenerator {

    private static final Logger log = LoggerFactory.getLogger(EntityGenerator.class);

    private static final Pattern FIELD_PATTERN = Pattern.compile("private\\s+([\\w\\.<>]+)\\s+(\\w+)\\s*;");

    private static final String ADDED_MARK = "// Added from DB column";
    private static final String DELETED_MARK_PREFIX = "// [DELETED FROM DB] Column '";
    private static final String RENAMED_MARK_PREFIX = "// [RENAMED?] This field may have been renamed to column '";
    private static final String TYPE_MISMATCH_PREFIX = "// [Type Mismatch] DB type:";
    private static final double RENAME_SCORE_THRESHOLD = 0.62;

    // Added 필드를 고정 영역에만 관리
    private static final String ADDED_BLOCK_BEGIN = "    // MyBatis-Easy: ADDED FIELDS BEGIN";
    private static final String ADDED_BLOCK_END   = "    // MyBatis-Easy: ADDED FIELDS END";

    private static final Pattern ADDED_BLOCK_PATTERN = Pattern.compile(
            "(?s)\\s*// MyBatis-Easy: ADDED FIELDS BEGIN\\s*.*?\\s*// MyBatis-Easy: ADDED FIELDS END\\s*",
            Pattern.MULTILINE
    );

    public void generate(DataSource dataSource,
                         String basePackage,
                         boolean useDbFolder,
                         boolean enableTablePackage,
                         String voRootPackage,
                         Map<String, List<String>> packageMapping,
                         String defaultModule) {

        generate(dataSource, basePackage, useDbFolder, enableTablePackage, voRootPackage, packageMapping, defaultModule,
                Paths.get("src/main/java"),
                Paths.get("src/main/resources/mapper"));
    }

    public void generate(DataSource dataSource,
                         String basePackage,
                         boolean useDbFolder,
                         boolean enableTablePackage,
                         String voRootPackage,
                         Map<String, List<String>> packageMapping,
                         String defaultModule,
                         Path javaRoot,
                         Path mapperRoot) {

        Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (basePackage == null || basePackage.isBlank()) {
            log.warn("MyBatis-Easy: basePackage is empty. Skip generation.");
            return;
        }

        // === additional internal safety gate (defense in depth) ===
        if (!isClearlyDevProject()) {
            log.warn("MyBatis-Easy: generator blocked (EntityGenerator defense). " +
                    "Need active dev project marker (.git under user.dir) to proceed.");
            return;
        }

        if (!isWritableProjectPath(javaRoot) || !isWritableProjectPath(mapperRoot)) {
            log.warn("MyBatis-Easy: target paths are not writable. " +
                            "This generator is intended for local development only. javaRoot={}, mapperRoot={}",
                    javaRoot.toAbsolutePath(), mapperRoot.toAbsolutePath());
            return;
        }

        Map<String, String> tableToModule = buildTableToModuleMap(packageMapping);
        String resolvedVoRoot = resolveVoRootPackage(voRootPackage, basePackage);
        String resolvedDefaultModule = (defaultModule == null || defaultModule.isBlank()) ? "misc" : defaultModule.trim();

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            String dbName = safeDbName(meta);
            Path xmlDir = useDbFolder ? mapperRoot.resolve(dbName) : mapperRoot;
            ensureDirectory(xmlDir);

            try (ResultSet tables = meta.getTables(null, null, null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    if (tableName == null || tableName.isBlank()) continue;

                    String className = convertToPascalCase(tableName);

                    String effectiveBasePackage = basePackage;
                    Path voDir = javaRoot.resolve(basePackage.replace(".", "/"));

                    if (enableTablePackage) {
                        String norm = normalizeTableName(tableName);
                        String module = tableToModule.getOrDefault(norm, resolvedDefaultModule);
                        effectiveBasePackage = resolvedVoRoot + "." + module + ".vo";
                        voDir = javaRoot.resolve(effectiveBasePackage.replace(".", "/"));
                    }

                    ensureDirectory(voDir);
                    Path javaFilePath = voDir.resolve(className + ".java");

                    DbTableSnapshot snapshot = readTableSnapshot(meta, tableName);

                    if (!Files.exists(javaFilePath)) {
                        createFullClass(snapshot, tableName, className, effectiveBasePackage, javaFilePath);
                    } else {
                        updateExistingClass(snapshot, tableName, className, effectiveBasePackage, javaFilePath);
                    }

                    String mapperName = className + "Mapper";
                    Path xmlFilePath = xmlDir.resolve(mapperName + ".xml");
                    if (!Files.exists(xmlFilePath)) {
                        createDefaultXml(basePackage, mapperName, xmlFilePath);
                    }
                }
            }

            log.info("MyBatis-Easy: Entity/XML generation completed. basePackage={}, useDbFolder={}, enableTablePackage={}, voRootPackage={}, db={}",
                    basePackage, useDbFolder, enableTablePackage, resolvedVoRoot, dbName);

        } catch (Exception e) {
            log.error("MyBatis-Easy: Generation failed", e);
        }
    }

    private boolean isClearlyDevProject() {
        try {
            String userDir = System.getProperty("user.dir");
            if (userDir == null || userDir.isBlank()) return false;
            Path git = Path.of(userDir).resolve(".git");
            return Files.exists(git) && Files.isDirectory(git);
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------
    // mapping helpers
    // -----------------------------

    private Map<String, String> buildTableToModuleMap(Map<String, List<String>> packageMapping) {
        Map<String, String> tableToModule = new LinkedHashMap<>();
        if (packageMapping == null || packageMapping.isEmpty()) {
            return tableToModule;
        }

        for (Map.Entry<String, List<String>> e : packageMapping.entrySet()) {
            String module = (e.getKey() == null) ? "" : e.getKey().trim();
            if (module.isBlank()) continue;

            List<String> tables = e.getValue();
            if (tables == null || tables.isEmpty()) continue;

            for (String t : tables) {
                if (t == null || t.isBlank()) continue;
                String norm = normalizeTableName(t);

                String prev = tableToModule.putIfAbsent(norm, module);
                if (prev != null && !prev.equals(module)) {
                    log.warn("MyBatis-Easy: table mapping conflict. table={} prevModule={} newModule={} -> keep prev",
                            norm, prev, module);
                }
            }
        }

        return tableToModule;
    }

    private String normalizeTableName(String tableName) {
        return tableName.trim().toUpperCase(Locale.ROOT);
    }

    private String resolveVoRootPackage(String voRootPackage, String basePackage) {
        if (voRootPackage != null && !voRootPackage.isBlank()) {
            return voRootPackage.trim();
        }

        if (basePackage == null) return "";
        if (basePackage.endsWith(".vo")) {
            return basePackage.substring(0, basePackage.length() - 3);
        }
        return basePackage;
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

        for (DbColumn col : snapshot.columns) {
            String colName = col.columnName;
            String fieldName = convertToCamelCase(colName);

            String javaType = fieldName.equalsIgnoreCase("id") ? "Long" : col.javaType;

            if ("id".equalsIgnoreCase(colName)) {
                sb.append("    @Id\n");
            }
            sb.append("    private ").append(javaType).append(" ").append(fieldName).append(";\n\n");
        }

        // Added 블록 기본 생성
        sb.append(ADDED_BLOCK_BEGIN).append("\n");
        sb.append(ADDED_BLOCK_END).append("\n\n");

        sb.append("}\n");
        Files.writeString(javaFilePath, sb.toString(), StandardCharsets.UTF_8);
        log.info("MyBatis-Easy: Created [{}]", javaFilePath.toAbsolutePath());
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
        Map<String, String> localFieldMap = parseLocalFields(content);

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

        Set<String> deletedCandidates = localFieldMap.keySet().stream()
                .filter(f -> !dbFieldNames.contains(f))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> addedCandidates = dbFieldNames.stream()
                .filter(f -> !localFieldMap.containsKey(f))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> renameDeletedToAdded = detectRenamePairs(
                deletedCandidates,
                addedCandidates,
                localFieldMap,
                dbFieldTypes
        );

        Set<String> matchedAdded = new HashSet<>(renameDeletedToAdded.values());
        Set<String> effectiveAdded = addedCandidates.stream()
                .filter(a -> !matchedAdded.contains(a))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean changed = false;

        // (1) deleted/renamed 마킹
        for (String fieldName : deletedCandidates) {
            String fieldType = localFieldMap.get(fieldName);
            if (fieldType == null) continue;

            String targetLine = "private " + fieldType + " " + fieldName + ";";
            if (!content.contains(targetLine)) continue;

            if (renameDeletedToAdded.containsKey(fieldName)) {
                String addedField = renameDeletedToAdded.get(fieldName);
                String newColumn = dbColumnByFieldName.getOrDefault(addedField, camelToSnake(addedField));
                String renameMsg = RENAMED_MARK_PREFIX + newColumn + "' (field: " + addedField + ")";

                content = removeMarkerDirectlyAbove(content, targetLine, RENAMED_MARK_PREFIX);
                if (!content.contains(renameMsg)) {
                    content = content.replace(targetLine, renameMsg + "\n    " + targetLine);
                    changed = true;
                    log.warn("MyBatis-Easy: Field [{}] in [{}] marked as RENAMED? -> {}",
                            fieldName, className, newColumn);
                }
                continue;
            }

            String deletedMsg = DELETED_MARK_PREFIX + camelToSnake(fieldName) + "' no longer exists in table " + tableName;

            content = removeMarkerDirectlyAbove(content, targetLine, DELETED_MARK_PREFIX);
            if (!content.contains(deletedMsg)) {
                content = content.replace(targetLine, deletedMsg + "\n    " + targetLine);
                changed = true;
                log.warn("MyBatis-Easy: Field [{}] in [{}] marked as DELETED (missing in DB).", fieldName, className);
            }
        }

        // (2) 타입 불일치 마킹
        for (String dbFieldName : dbFieldNames) {
            if (!localFieldMap.containsKey(dbFieldName)) continue;

            String existingType = localFieldMap.get(dbFieldName);
            String dbJavaType = dbFieldTypes.get(dbFieldName);

            if (existingType != null && dbJavaType != null && !existingType.equals(dbJavaType)) {
                String typeWarning = TYPE_MISMATCH_PREFIX + " " + dbJavaType + " (Current: " + existingType + ")";
                String targetLine = "private " + existingType + " " + dbFieldName + ";";

                content = removeMarkerDirectlyAbove(content, targetLine, TYPE_MISMATCH_PREFIX);

                if (content.contains(targetLine) && !content.contains(typeWarning)) {
                    content = content.replace(targetLine, typeWarning + "\n    " + targetLine);
                    changed = true;
                }
            }
        }

        // Added 필드는 고정 블록 내부에만 추가
        if (!effectiveAdded.isEmpty()) {
            String updated = upsertAddedFieldsBlock(content, effectiveAdded, dbFieldTypes);
            if (!updated.equals(content)) {
                content = updated;
                changed = true;
            }
        }

        if (!changed) return;

        Files.writeString(javaFilePath, content, StandardCharsets.UTF_8);
        log.info("MyBatis-Easy: Synced [{}] (Added/TypeCheck/DeletedCheck/RenameHint)", javaFilePath.toAbsolutePath());
    }

    private String removeMarkerDirectlyAbove(String content, String targetLine, String markerPrefix) {
        if (content == null || content.isBlank()) return content;
        if (targetLine == null || targetLine.isBlank()) return content;
        if (markerPrefix == null || markerPrefix.isBlank()) return content;

        String regex = "(?m)^\\s*" + Pattern.quote(markerPrefix) + ".*\\R\\s*"
                + Pattern.quote(targetLine) + "\\s*$";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(content);
        if (m.find()) {
            String replacement = "    " + targetLine;
            return content.substring(0, m.start()) + replacement + content.substring(m.end());
        }
        return content;
    }

    private String upsertAddedFieldsBlock(String content,
                                         Set<String> effectiveAdded,
                                         Map<String, String> dbFieldTypes) {

        if (content == null || content.isBlank()) return content;

        Matcher m = ADDED_BLOCK_PATTERN.matcher(content);
        if (m.find()) {
            String block = m.group(0);

            Set<String> alreadyDeclared = parseLocalFields(block).keySet();

            StringBuilder append = new StringBuilder();
            for (String dbFieldName : effectiveAdded) {
                if (alreadyDeclared.contains(dbFieldName)) continue;

                String dbJavaType = dbFieldTypes.getOrDefault(dbFieldName, "String");
                append.append("    private ").append(dbJavaType).append(" ").append(dbFieldName).append("; ")
                        .append(ADDED_MARK).append("\n\n");
            }

            if (append.isEmpty()) return content;

            String newBlock = block.replace(ADDED_BLOCK_END, append + ADDED_BLOCK_END);
            return content.substring(0, m.start()) + newBlock + content.substring(m.end());
        }

        int lastBrace = content.lastIndexOf('}');
        if (lastBrace < 0) return content;

        StringBuilder fields = new StringBuilder();
        for (String dbFieldName : effectiveAdded) {
            String dbJavaType = dbFieldTypes.getOrDefault(dbFieldName, "String");
            fields.append("    private ").append(dbJavaType).append(" ").append(dbFieldName).append("; ")
                    .append(ADDED_MARK).append("\n\n");
        }
        if (fields.isEmpty()) return content;

        String block = "\n" + ADDED_BLOCK_BEGIN + "\n" + fields + ADDED_BLOCK_END + "\n";
        return content.substring(0, lastBrace).trim() + block + "}\n";
    }

    private Map<String, String> detectRenamePairs(Set<String> deletedCandidates,
                                                 Set<String> addedCandidates,
                                                 Map<String, String> localFieldMap,
                                                 Map<String, String> dbFieldTypes) {

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

    private double similarityScore(String a, String b) {
        String na = normalizeName(a);
        String nb = normalizeName(b);

        if (na.equals(nb)) return 1.0;
        if (na.contains(nb) || nb.contains(na)) return 0.92;

        int dist = levenshteinDistance(na, nb);
        int max = Math.max(na.length(), nb.length());
        if (max == 0) return 0.0;

        double ratio = 1.0 - ((double) dist / (double) max);
        if (max <= 4) ratio -= 0.08;

        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private String normalizeName(String s) {
        if (s == null) return "";
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
        log.info("MyBatis-Easy: Created mapper xml [{}]", xmlFilePath.toAbsolutePath());
    }

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
