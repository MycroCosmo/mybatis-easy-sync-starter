package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import com.thenoah.dev.mybatis_easy_starter.support.ColumnAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EntityGenerator {
    private static final Logger log = LoggerFactory.getLogger(EntityGenerator.class);

    // 필드 추출용 패턴 (기존 파일 텍스트 분석용)
    private static final Pattern FIELD_PATTERN = Pattern.compile("private\\s+([\\w\\.<>]+)\\s+(\\w+);");

    /**
     * DB의 모든 테이블을 스캔하여 VO 및 XML 매퍼를 생성하거나 동기화합니다.
     */
    public void generate(DataSource dataSource, String basePackage, boolean useDbFolder) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            String dbName = meta.getDatabaseProductName().toLowerCase().replaceAll("\\s", "");
            String voPath = "src/main/java/" + basePackage.replace(".", "/");
            String xmlPath = "src/main/resources/mapper";

            if (useDbFolder) {
                xmlPath += "/" + dbName;
            }

            try {
                if (!Files.exists(Paths.get(voPath))) {
                    Files.createDirectories(Paths.get(voPath));
                }
                if (!Files.exists(Paths.get(xmlPath))) {
                    Files.createDirectories(Paths.get(xmlPath));
                }
            } catch (java.io.IOException e) {
                log.error("MyBatis-Easy: Failed to create directories", e);
                return;
            }

            ResultSet tables = meta.getTables(null, null, null, new String[]{"TABLE"});
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                String className = convertToPascalCase(tableName);

                File javaFile = new File(voPath, className + ".java");

                if (!javaFile.exists()) {
                    createFullClass(meta, tableName, className, basePackage, javaFile);
                } else {
                    // 타입 변경 체크 + 신규 필드 추가 + 삭제된 필드 주석화 로직 통합 호출
                    updateExistingClass(meta, tableName, className, basePackage, javaFile);
                }

                String mapperName = className + "Mapper";
                File xmlFile = new File(xmlPath, mapperName + ".xml");
                if (!xmlFile.exists()) {
                    createDefaultXml(basePackage, mapperName, xmlFile);
                }
            }
            log.info("MyBatis-Easy: Full Generation and sync completed.");
        } catch (Exception e) {
            log.error("MyBatis-Easy: Generation failed", e);
        }
    }

    private void createFullClass(DatabaseMetaData meta, String tableName, String className, String basePackage, File file) throws Exception {
        String aliasName = className.substring(0, 1).toLowerCase() + className.substring(1);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(";\n\n")
                .append("import com.thenoah.dev.mybatis_easy_starter.core.annotation.*;\n")
                .append("import org.apache.ibatis.type.Alias;\n")
                .append("import lombok.Data;\n")
                .append("import java.time.LocalDateTime;\n\n")
                .append("@Data\n")
                .append("@Alias(\"").append(aliasName).append("\")\n")
                .append("@Table(name = \"").append(tableName).append("\")\n")
                .append("public class ").append(className).append(" {\n");

        ResultSet cols = meta.getColumns(null, null, tableName, null);
        while (cols.next()) {
            String colName = cols.getString("COLUMN_NAME");
            String fieldName = convertToCamelCase(colName);
            String javaType = fieldName.equalsIgnoreCase("id") ? "Long" : mapSqlTypeToJavaType(cols.getInt("DATA_TYPE"));
            if ("id".equalsIgnoreCase(colName)) sb.append("    @Id\n");
            sb.append("    private ").append(javaType).append(" ").append(fieldName).append(";\n");
        }
        sb.append("}\n");
        Files.writeString(file.toPath(), sb.toString());
        log.info("MyBatis-Easy: Created [{}.java]", className);
    }

    private void updateExistingClass(DatabaseMetaData meta, String tableName, String className, String basePackage, File file) throws Exception {
        String content = Files.readString(file.toPath());

        // 1. 현재 파일의 필드 분석 (필드명 -> 타입)
        Map<String, String> localFieldMap = new HashMap<>();
        Matcher fieldMatcher = FIELD_PATTERN.matcher(content);
        while (fieldMatcher.find()) {
            localFieldMap.put(fieldMatcher.group(2), fieldMatcher.group(1));
        }

        // 2. DB 최신 메타데이터 수집
        Set<String> dbFieldNames = new HashSet<>();
        Map<String, String> dbFieldTypes = new HashMap<>();
        ResultSet cols = meta.getColumns(null, null, tableName, null);
        while (cols.next()) {
            String colName = cols.getString("COLUMN_NAME");
            String fieldName = convertToCamelCase(colName);
            String dbJavaType = fieldName.equalsIgnoreCase("id") ? "Long" : mapSqlTypeToJavaType(cols.getInt("DATA_TYPE"));
            dbFieldNames.add(fieldName);
            dbFieldTypes.put(fieldName, dbJavaType);
        }

        boolean isChanged = false;

        // 3. 삭제된 필드 감지 (VO에는 있는데 DB에는 없는 경우 주석 추가)
        for (Map.Entry<String, String> entry : localFieldMap.entrySet()) {
            String fieldName = entry.getKey();
            String fieldType = entry.getValue();

            if (!dbFieldNames.contains(fieldName)) {
                String warningMsg = "// [DELETED FROM DB] Column '" + camelToSnake(fieldName) + "' no longer exists in table";
                String targetLine = "private " + fieldType + " " + fieldName + ";";
                
                // 이미 주석이 달려있지 않은 경우에만 추가
                if (!content.contains(warningMsg)) {
                    content = content.replace(targetLine, warningMsg + "\n    " + targetLine);
                    isChanged = true;
                    log.warn("MyBatis-Easy: Field [{}] in [{}] marked as DELETED because it's missing in DB.", fieldName, className);
                }
            }
        }

        // 4. 타입 변경 체크 및 신규 필드 추가 준비
        StringBuilder newFields = new StringBuilder();
        Set<String> allFieldNamesInClass = new HashSet<>();
        try {
            // 상속 구조 포함 전체 필드 확인
            Class<?> clazz = Class.forName(basePackage + "." + className);
            allFieldNamesInClass = ColumnAnalyzer.getAllFields(clazz).stream().map(Field::getName).collect(Collectors.toSet());
        } catch (ClassNotFoundException e) {
            allFieldNamesInClass.addAll(localFieldMap.keySet());
        }

        for (String dbFieldName : dbFieldNames) {
            String dbJavaType = dbFieldTypes.get(dbFieldName);

            if (!allFieldNamesInClass.contains(dbFieldName)) {
                // 신규 필드 추가
                newFields.append("\n    private ").append(dbJavaType).append(" ").append(dbFieldName).append(";")
                        .append(" // Added from DB column\n");
                isChanged = true;
            } else if (localFieldMap.containsKey(dbFieldName)) {
                // 기존 필드 타입 정합성 체크
                String existingType = localFieldMap.get(dbFieldName);
                if (!existingType.equals(dbJavaType)) {
                    String typeWarning = "// [Type Mismatch] DB type: " + dbJavaType + " (Current: " + existingType + ")";
                    String targetLine = "private " + existingType + " " + dbFieldName + ";";
                    if (!content.contains(typeWarning)) {
                        content = content.replace(targetLine, typeWarning + "\n    " + targetLine);
                        isChanged = true;
                    }
                }
            }
        }

        if (isChanged) {
            int lastBrace = content.lastIndexOf("}");
            String updated = content.substring(0, lastBrace) + newFields.toString() + "}\n";
            Files.writeString(file.toPath(), updated);
            log.info("MyBatis-Easy: Synced [{}.java] (Added/TypeCheck/DeletedCheck)", className);
        }
    }

    private String mapSqlTypeToJavaType(int type) {
        return switch (type) {
            case Types.BIGINT -> "Long";
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "Integer";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "LocalDateTime";
            case Types.DATE -> "java.time.LocalDate";
            case Types.BOOLEAN, Types.BIT -> "Boolean";
            case Types.DOUBLE, Types.FLOAT -> "Double";
            case Types.DECIMAL, Types.NUMERIC -> "java.math.BigDecimal";
            default -> "String";
        };
    }

    private void createDefaultXml(String basePackage, String mapperName, File file) throws Exception {
        String rootPackage = basePackage.contains(".") ?
                basePackage.substring(0, basePackage.lastIndexOf(".")) : basePackage;
        String namespace = rootPackage + ".mapper." + mapperName;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                .append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n")
                .append("<mapper namespace=\"").append(namespace).append("\">\n\n")
                .append("</mapper>");
        Files.writeString(file.toPath(), sb.toString());
    }

    private String convertToPascalCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Arrays.stream(s.split("_")).filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase())
                .collect(Collectors.joining());
    }

    private String convertToCamelCase(String s) {
        String p = convertToPascalCase(s);
        return (p == null || p.isEmpty()) ? p : p.substring(0, 1).toLowerCase() + p.substring(1);
    }

    private String camelToSnake(String str) {
        return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }
}