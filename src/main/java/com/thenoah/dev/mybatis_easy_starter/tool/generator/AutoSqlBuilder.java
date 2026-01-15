package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import com.thenoah.dev.mybatis_easy_starter.config.MybatisEasyProperties;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.support.ColumnAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AutoSqlBuilder {

  private static final Logger log = LoggerFactory.getLogger(AutoSqlBuilder.class);

  // ✅ 태그 기반 + 따옴표(" 또는 ') 지원
  private static final Pattern ID_INSERT =
      Pattern.compile("<insert\\b[^>]*\\bid\\s*=\\s*([\"'])insert\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_FIND_BY_ID =
      Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*([\"'])findById\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_FIND_ALL =
      Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*([\"'])findAll\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_UPDATE =
      Pattern.compile("<update\\b[^>]*\\bid\\s*=\\s*([\"'])update\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_DELETE_BY_ID =
      Pattern.compile("<(delete|update)\\b[^>]*\\bid\\s*=\\s*([\"'])deleteById\\2", Pattern.CASE_INSENSITIVE);

  private static final String DEFAULT_NOW_FUNCTION = "CURRENT_TIMESTAMP";

  public static String build(Class<?> entityClass,
                             String userXmlContent,
                             MybatisEasyProperties.AutoSql autoSqlProps,
                             String dbProductName) {
    try {
      ColumnAnalyzer.TableInfo tableInfo = ColumnAnalyzer.analyzeClass(entityClass);

      String tableName = tableInfo.getTableName();
      String resultTypeName = entityClass.getName();

      List<Field> fields = ColumnAnalyzer.getAllFields(entityClass);

      // ✅ PK 정책을 ColumnAnalyzer 결과에 맞춘다 (일관성)
      String pkColumn = (tableInfo.getIdColumn() == null || tableInfo.getIdColumn().isBlank())
          ? "id"
          : tableInfo.getIdColumn();
      String pkProperty = (tableInfo.getIdField() == null || tableInfo.getIdField().isBlank())
          ? "id"
          : tableInfo.getIdField();

      Field softDeleteField = fields.stream()
          .filter(f -> f.isAnnotationPresent(SoftDelete.class))
          .findFirst()
          .orElse(null);

      boolean allowEmptySet = autoSqlProps != null
          && autoSqlProps.getUpdate() != null
          && autoSqlProps.getUpdate().isAllowEmptySet();

      // generated key
      MybatisEasyProperties.AutoSql.GeneratedKey gk = (autoSqlProps != null) ? autoSqlProps.getGeneratedKey() : null;
      MybatisEasyProperties.AutoSql.Strategy configured =
          (gk != null) ? gk.getStrategy() : MybatisEasyProperties.AutoSql.Strategy.AUTO;

      String keyColumn = (gk != null && gk.getKeyColumn() != null && !gk.getKeyColumn().isBlank())
          ? gk.getKeyColumn().trim()
          : "id";

      MybatisEasyProperties.AutoSql.Strategy strategy = resolveStrategy(dbProductName, configured);

      // ✅ SELECT * 제거: 분석된 컬럼 리스트 사용
      String selectColumns = tableInfo.getFieldColumnMap().values().stream()
          .distinct()
          .collect(Collectors.joining(", "));
      if (selectColumns.isBlank()) selectColumns = "*";

      StringBuilder sql = new StringBuilder(2048);

      if (!exists(userXmlContent, ID_INSERT)) {
        sql.append(buildInsert(tableName, fields, pkProperty, keyColumn, strategy));
      }
      if (!exists(userXmlContent, ID_FIND_BY_ID)) {
        sql.append(buildFindById(tableName, selectColumns, pkColumn, pkProperty, resultTypeName, softDeleteField));
      }
      if (!exists(userXmlContent, ID_FIND_ALL)) {
        sql.append(buildFindAll(tableName, selectColumns, resultTypeName, softDeleteField));
      }
      if (!exists(userXmlContent, ID_UPDATE)) {
        sql.append(buildUpdate(tableName, fields, pkColumn, pkProperty, softDeleteField, allowEmptySet));
      }
      if (!exists(userXmlContent, ID_DELETE_BY_ID)) {
        sql.append(buildDeleteById(tableName, pkColumn, pkProperty, softDeleteField));
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

  private static MybatisEasyProperties.AutoSql.Strategy resolveStrategy(String dbProductName,
                                                                       MybatisEasyProperties.AutoSql.Strategy configured) {
    if (configured != null && configured != MybatisEasyProperties.AutoSql.Strategy.AUTO) {
      return configured;
    }
    // AUTO: safest는 JDBC(useGeneratedKeys) fallback
    String db = (dbProductName == null) ? "" : dbProductName.toLowerCase(Locale.ROOT);
    if (db.contains("oracle")) {
      return MybatisEasyProperties.AutoSql.Strategy.JDBC;
    }
    return MybatisEasyProperties.AutoSql.Strategy.JDBC;
  }

  /**
   * INSERT:
   * - PK 필드 제외 (pkProperty 기준)
   * - null 아닌 것만 insert
   * - 전부 null이면 DEFAULT VALUES
   */
  private static String buildInsert(String tableName,
                                    List<Field> fields,
                                    String pkProperty,
                                    String keyColumn,
                                    MybatisEasyProperties.AutoSql.Strategy strategy) {

    List<Field> nonPkFields = fields.stream()
        .filter(f -> !isPkField(f, pkProperty))
        .collect(Collectors.toList());

    String anyNotNullTest = buildAnyNotNullTestByProperty(nonPkFields);

    StringBuilder sb = new StringBuilder();

    if (strategy == MybatisEasyProperties.AutoSql.Strategy.JDBC) {
      sb.append("  <insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"")
          .append(pkProperty)
          .append("\" keyColumn=\"")
          .append(keyColumn)
          .append("\">\n");
    } else {
      sb.append("  <insert id=\"insert\">\n");
    }

    sb.append("    <choose>\n")
        .append("      <when test=\"").append(anyNotNullTest).append("\">\n")
        .append("        INSERT INTO ").append(tableName).append("\n")
        .append("        <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");

    for (Field f : nonPkFields) {
      String col = ColumnAnalyzer.getColumnName(f);
      String prop = f.getName();
      sb.append("          <if test=\"").append(prop).append(" != null\">")
          .append(col).append(",</if>\n");
    }

    sb.append("        </trim>\n")
        .append("        <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">\n");

    for (Field f : nonPkFields) {
      String prop = f.getName();
      sb.append("          <if test=\"").append(prop).append(" != null\">")
          .append("#{").append(prop).append("},</if>\n");
    }

    sb.append("        </trim>\n")
        .append("      </when>\n")
        .append("      <otherwise>\n")
        .append("        INSERT INTO ").append(tableName).append(" DEFAULT VALUES\n")
        .append("      </otherwise>\n")
        .append("    </choose>\n")
        .append("  </insert>\n\n");

    return sb.toString();
  }

  private static String buildAnyNotNullTestByProperty(List<Field> fields) {
    if (fields == null || fields.isEmpty()) return "false";
    return fields.stream()
        .map(f -> f.getName() + " != null")
        .collect(Collectors.joining(" or "));
  }

  private static String buildFindById(String tableName,
                                      String selectColumns,
                                      String pkColumn,
                                      String pkProperty,
                                      String resultTypeName,
                                      Field softDeleteField) {
    StringBuilder sb = new StringBuilder();

    sb.append("  <select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
        .append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n")
        .append("    WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n");

    if (softDeleteField != null) {
      String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
      sb.append("    AND ").append(sdCol).append(" IS NULL\n");
    }

    sb.append("  </select>\n\n");
    return sb.toString();
  }

  private static String buildFindAll(String tableName,
                                     String selectColumns,
                                     String resultTypeName,
                                     Field softDeleteField) {
    StringBuilder sb = new StringBuilder();

    sb.append("  <select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n")
        .append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n");

    if (softDeleteField != null) {
      String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
      sb.append("    WHERE ").append(sdCol).append(" IS NULL\n");
    }

    sb.append("  </select>\n\n");
    return sb.toString();
  }

  /**
   * UPDATE: pkProperty 기준으로 PK 제외
   */
  private static String buildUpdate(String tableName,
                                    List<Field> fields,
                                    String pkColumn,
                                    String pkProperty,
                                    Field softDeleteField,
                                    boolean allowEmptySet) {

    boolean hasUpdatedAt = fields.stream()
        .map(ColumnAnalyzer::getColumnName)
        .anyMatch(c -> "updated_at".equalsIgnoreCase(c));

    List<Field> updatableFields = fields.stream()
        .filter(f -> !isPkField(f, pkProperty))
        .filter(f -> !"updated_at".equalsIgnoreCase(ColumnAnalyzer.getColumnName(f)))
        .collect(Collectors.toList());

    String nonEmptyTest = updatableFields.isEmpty()
        ? "false"
        : updatableFields.stream().map(f -> f.getName() + " != null").collect(Collectors.joining(" or "));

    StringBuilder sb = new StringBuilder();
    sb.append("  <update id=\"update\">\n");

    if (!allowEmptySet) {
      sb.append("    <choose>\n")
          .append("      <when test=\"").append(nonEmptyTest).append("\">\n");
    }

    sb.append("        UPDATE ").append(tableName).append("\n")
        .append("        <set>\n");

    for (Field f : updatableFields) {
      String col = ColumnAnalyzer.getColumnName(f);
      String prop = f.getName();
      sb.append("          <if test=\"").append(prop).append(" != null\">")
          .append(col).append(" = #{").append(prop).append("},</if>\n");
    }

    if (hasUpdatedAt) {
      sb.append("          updated_at = ").append(DEFAULT_NOW_FUNCTION).append(",\n");
    }

    sb.append("        </set>\n")
        .append("        WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n");

    if (softDeleteField != null) {
      String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
      sb.append("        AND ").append(sdCol).append(" IS NULL\n");
    }

    if (!allowEmptySet) {
      sb.append("      </when>\n")
          .append("      <otherwise>\n")
          .append("        UPDATE ").append(tableName).append(" SET ").append(pkColumn).append(" = ").append(pkColumn).append("\n")
          .append("        WHERE 1 = 0\n")
          .append("      </otherwise>\n")
          .append("    </choose>\n");
    }

    sb.append("  </update>\n\n");
    return sb.toString();
  }

  private static String buildDeleteById(String tableName,
                                        String pkColumn,
                                        String pkProperty,
                                        Field softDeleteField) {
    StringBuilder sb = new StringBuilder();

    if (softDeleteField != null) {
      String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
      sb.append("  <update id=\"deleteById\">\n")
          .append("    UPDATE ").append(tableName).append("\n")
          .append("    SET ").append(sdCol).append(" = ").append(DEFAULT_NOW_FUNCTION).append("\n")
          .append("    WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n")
          .append("  </update>\n\n");
    } else {
      sb.append("  <delete id=\"deleteById\">\n")
          .append("    DELETE FROM ").append(tableName).append("\n")
          .append("    WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n")
          .append("  </delete>\n\n");
    }

    return sb.toString();
  }

  private static boolean isPkField(Field f, String pkProperty) {
    if (f == null) return false;
    if (pkProperty == null || pkProperty.isBlank()) return false;

    // ✅ 정책 일치: pkProperty로 판정
    if (pkProperty.equals(f.getName())) return true;

    // @Id가 붙어있으면 pkProperty로 잡히는 게 정상인데,
    // 혹시 TableInfo가 idField를 못 잡은 케이스를 위한 최소 방어
    return f.isAnnotationPresent(Id.class) && "id".equals(pkProperty);
  }
}
