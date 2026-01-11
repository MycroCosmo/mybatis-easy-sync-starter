package com.thenoah.dev.mybatis_easy_starter.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EntityGenerator {
    private static final Logger log = LoggerFactory.getLogger(EntityGenerator.class);

    // 필드 추출용 패턴: [1] 타입명, [2] 변수명
    private static final Pattern FIELD_PATTERN = Pattern.compile("private\\s+([\\w\\.<>]+)\\s+(\\w+);");

    /**
     * 실행 시 바로 생성 및 동기화 로직을 수행합니다.
     * (AutoConfiguration에서 @ConditionalOnProperty로 필터링되어 호출되므로 enabled 파라미터가 불필요해집니다.)
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

            File voDirectory = new File(voPath);
            File xmlDirectory = new File(xmlPath);

            if (!voDirectory.exists()) voDirectory.mkdirs();
            if (!xmlDirectory.exists()) xmlDirectory.mkdirs();

            ResultSet tables = meta.getTables(null, null, null, new String[]{"TABLE"});
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                String className = convertToPascalCase(tableName);

                File javaFile = new File(voDirectory, className + ".java");
                if (!javaFile.exists()) {
                    createFullClass(meta, tableName, className, basePackage, javaFile);
                } else {
                    // 주석 알림 및 신규 필드 추가 로직
                    updateExistingClass(meta, tableName, javaFile);
                }

                String mapperName = className + "Mapper";
                File xmlFile = new File(xmlDirectory, mapperName + ".xml");
                if (!xmlFile.exists()) {
                    createDefaultXml(basePackage, mapperName, xmlFile);
                }
            }
            log.info("MyBatis-Easy: Generation and sync completed.");
        } catch (Exception e) {
            log.error("MyBatis-Easy: Generation failed", e);
        }
    }

    // [제거됨] validateSchema 메서드 - 더 이상 구동 시 정합성 체크 로그를 남기지 않음

    private void createFullClass(DatabaseMetaData meta, String tableName, String className, String basePackage, File file) throws Exception {
        String aliasName = className.substring(0, 1).toLowerCase() + className.substring(1);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(";\n\n")
                .append("import com.thenoah.dev.mybatis_easy_starter.core.*;\n")
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

    private void updateExistingClass(DatabaseMetaData meta, String tableName, File file) throws Exception {
        String content = Files.readString(file.toPath());
        Map<String, String> fieldMap = new HashMap<>();
        Matcher fieldMatcher = FIELD_PATTERN.matcher(content);
        while (fieldMatcher.find()) {
            fieldMap.put(fieldMatcher.group(2), fieldMatcher.group(1));
        }

        StringBuilder newFields = new StringBuilder();
        ResultSet cols = meta.getColumns(null, null, tableName, null);
        boolean isChanged = false;

        while (cols.next()) {
            String colName = cols.getString("COLUMN_NAME");
            String fieldName = convertToCamelCase(colName);
            String dbJavaType = fieldName.equalsIgnoreCase("id") ? "Long" : mapSqlTypeToJavaType(cols.getInt("DATA_TYPE"));

            if (!fieldMap.containsKey(fieldName)) {
                newFields.append("\n    private ").append(dbJavaType).append(" ").append(fieldName).append(";")
                        .append(" // Added from DB column '").append(colName).append("'\n");
                isChanged = true;
            } else {
                String existingType = fieldMap.get(fieldName);
                if (!existingType.equals(dbJavaType)) {
                    // 리더의 로컬에서 주석을 생성하고, 이 파일이 Git에 Push되어 팀원들에게 전달됨
                    String warningMsg = "// [Type Warning] DB type is " + dbJavaType + " (Current: " + existingType + ")";
                    if (!content.contains(warningMsg)) {
                        String targetLine = "private " + existingType + " " + fieldName + ";";
                        content = content.replace(targetLine, warningMsg + "\n    " + targetLine);
                        isChanged = true;
                    }
                }
            }
        }

        if (isChanged) {
            int lastBrace = content.lastIndexOf("}");
            String updated = content.substring(0, lastBrace) + newFields.toString() + "}\n";
            Files.writeString(file.toPath(), updated);
            log.info("MyBatis-Easy: Synced fields in [{}]", file.getName());
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
}