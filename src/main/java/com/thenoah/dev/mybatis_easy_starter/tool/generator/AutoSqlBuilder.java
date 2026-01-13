package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.support.ColumnAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.regex.Pattern;

public class AutoSqlBuilder {

    private static final Logger log = LoggerFactory.getLogger(AutoSqlBuilder.class);

    private static final Pattern ID_INSERT = Pattern.compile("id\\s*=\\s*\"insert\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_FIND_BY_ID = Pattern.compile("id\\s*=\\s*\"findById\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_FIND_ALL = Pattern.compile("id\\s*=\\s*\"findAll\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_UPDATE = Pattern.compile("id\\s*=\\s*\"update\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_DELETE_BY_ID = Pattern.compile("id\\s*=\\s*\"deleteById\"", Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_NOW_FUNCTION = "CURRENT_TIMESTAMP";

    public static String build(Class<?> entityClass, String userXmlContent) {
        try {
            ColumnAnalyzer.TableInfo tableInfo = ColumnAnalyzer.analyzeClass(entityClass);

            String tableName = tableInfo.getTableName();
            String resultTypeName = entityClass.getName();

            List<Field> fields = ColumnAnalyzer.getAllFields(entityClass);

            Field pkField = fields.stream().filter(f -> f.isAnnotationPresent(Id.class)).findFirst().orElse(null);
            Field softDeleteField = fields.stream().filter(f -> f.isAnnotationPresent(SoftDelete.class)).findFirst().orElse(null);

            // DB 컬럼명 기준으로 통일
            String pkColumn = (pkField != null) ? ColumnAnalyzer.getColumnName(pkField) : "id";
            // keyProperty는 VO 필드명을 써야 insert 후 객체에 세팅 가능(VO 케이스)
            String pkProperty = (pkField != null) ? pkField.getName() : "id";

            StringBuilder sql = new StringBuilder(2048);

            if (!exists(userXmlContent, ID_INSERT)) {
                sql.append(buildInsert(tableName, fields, pkProperty));
            }
            if (!exists(userXmlContent, ID_FIND_BY_ID)) {
                sql.append(buildFindById(tableName, pkColumn, resultTypeName, softDeleteField));
            }
            if (!exists(userXmlContent, ID_FIND_ALL)) {
                sql.append(buildFindAll(tableName, resultTypeName, softDeleteField));
            }
            if (!exists(userXmlContent, ID_UPDATE)) {
                sql.append(buildUpdate(tableName, fields, pkColumn, pkField, softDeleteField));
            }
            if (!exists(userXmlContent, ID_DELETE_BY_ID)) {
                sql.append(buildDeleteById(tableName, pkColumn, softDeleteField));
            }

            return sql.toString();
        } catch (Exception e) {
            log.error("AutoSqlBuilder failed", e);
            return "";
        }
    }

    private static boolean exists(String xml, Pattern pattern) {
        if (xml == null || xml.isBlank()) return false;
        return pattern.matcher(xml).find();
    }

    /**
     * INSERT:
     * - ID 컬럼은 기본 제외(자동 생성 가정)
     * - null이 아닌 값만 insert
     *
     * IMPORTANT:
     * - test/#{...}는 "DB 컬럼명"을 key로 사용 (DTO/VO 공통)
     */
    private static String buildInsert(String tableName, List<Field> fields, String pkProperty) {
        StringBuilder sb = new StringBuilder();

        sb.append("  <insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"").append(pkProperty).append("\">\n")
                .append("    INSERT INTO ").append(tableName).append("\n")
                .append("    <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");

        for (Field f : fields) {
            if (isIdField(f)) continue;

            String col = ColumnAnalyzer.getColumnName(f); // DB 컬럼명
            sb.append("      <if test=\"").append(col).append(" != null\">").append(col).append(",</if>\n");
        }

        sb.append("    </trim>\n")
                .append("    <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">\n");

        for (Field f : fields) {
            if (isIdField(f)) continue;

            String col = ColumnAnalyzer.getColumnName(f); // DB 컬럼명
            sb.append("      <if test=\"").append(col).append(" != null\">#{").append(col).append("},</if>\n");
        }

        sb.append("    </trim>\n")
                .append("  </insert>\n\n");

        return sb.toString();
    }

    private static String buildFindById(String tableName, String pkColumn, String resultTypeName, Field softDeleteField) {
        StringBuilder sb = new StringBuilder();

        sb.append("  <select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
                .append("    SELECT * FROM ").append(tableName).append("\n")
                .append("    WHERE ").append(pkColumn).append(" = #{id}\n");

        if (softDeleteField != null) {
            String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
            sb.append("    AND ").append(sdCol).append(" IS NULL\n");
        }

        sb.append("  </select>\n\n");
        return sb.toString();
    }

    private static String buildFindAll(String tableName, String resultTypeName, Field softDeleteField) {
        StringBuilder sb = new StringBuilder();

        sb.append("  <select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n")
                .append("    SELECT * FROM ").append(tableName).append("\n");

        if (softDeleteField != null) {
            String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
            sb.append("    WHERE ").append(sdCol).append(" IS NULL\n");
        }

        sb.append("  </select>\n\n");
        return sb.toString();
    }

    /**
     * UPDATE:
     * - ID 제외
     * - null이 아닌 값만 set
     * - updated_at 컬럼이 있으면 자동 세팅
     *
     * IMPORTANT:
     * - test/#{...}는 "DB 컬럼명" 키를 사용
     * - WHERE PK는 VO/DTO 모두 처리:
     *   - VO: pkProperty 필드가 있으면 보통 Mapper 호출 시 VO가 들어오지만, Interceptor가 Map으로 바꾸므로 결국 Map 키로 봐야 함
     *   - DTO: Map에도 pkColumn 키가 들어오도록 해야 함 (EntityParser가 @Column 또는 NamingStrategy 기반으로 넣는다면 가능)
     */
    private static String buildUpdate(String tableName,
                                      List<Field> fields,
                                      String pkColumn,
                                      Field pkField,
                                      Field softDeleteField) {
        StringBuilder sb = new StringBuilder();

        sb.append("  <update id=\"update\">\n")
                .append("    UPDATE ").append(tableName).append("\n")
                .append("    <set>\n");

        boolean hasUpdatedAt = false;

        for (Field f : fields) {
            if (isIdField(f)) continue;

            String col = ColumnAnalyzer.getColumnName(f);

            if ("updated_at".equalsIgnoreCase(col)) {
                hasUpdatedAt = true;
                continue;
            }

            sb.append("      <if test=\"").append(col).append(" != null\">")
                    .append(col).append(" = #{").append(col).append("},</if>\n");
        }

        if (hasUpdatedAt) {
            sb.append("      updated_at = ").append(DEFAULT_NOW_FUNCTION).append(",\n");
        }

        // WHERE는 pkColumn 기준(컬럼명 key)
        // => update 호출 파라미터 Map에 pkColumn 키가 있어야 동작
        sb.append("    </set>\n")
                .append("    WHERE ").append(pkColumn).append(" = #{").append(pkColumn).append("}\n");

        if (softDeleteField != null) {
            String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
            sb.append("    AND ").append(sdCol).append(" IS NULL\n");
        }

        sb.append("  </update>\n\n");
        return sb.toString();
    }

    private static String buildDeleteById(String tableName, String pkColumn, Field softDeleteField) {
        StringBuilder sb = new StringBuilder();

        if (softDeleteField != null) {
            String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
            sb.append("  <update id=\"deleteById\">\n")
                    .append("    UPDATE ").append(tableName).append("\n")
                    .append("    SET ").append(sdCol).append(" = ").append(DEFAULT_NOW_FUNCTION).append("\n")
                    .append("    WHERE ").append(pkColumn).append(" = #{id}\n")
                    .append("  </update>\n\n");
        } else {
            sb.append("  <delete id=\"deleteById\">\n")
                    .append("    DELETE FROM ").append(tableName).append("\n")
                    .append("    WHERE ").append(pkColumn).append(" = #{id}\n")
                    .append("  </delete>\n\n");
        }

        return sb.toString();
    }

    private static boolean isIdField(Field f) {
        return f.isAnnotationPresent(Id.class) || "id".equalsIgnoreCase(f.getName());
    }
}
