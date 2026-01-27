package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import com.thenoah.dev.mybatis_easy_starter.config.MybatisEasyProperties;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.support.ColumnAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AutoSqlBuilder {

  private static final Logger log = LoggerFactory.getLogger(AutoSqlBuilder.class);

  private static final Pattern ID_INSERT =
      Pattern.compile("<insert\\b[^>]*\\bid\\s*=\\s*([\"'])insert\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_FIND_BY_ID =
      Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*([\"'])findById\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_FIND_ALL =
      Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*([\"'])findAll\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_FIND_PAGE =
      Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*([\"'])findPage\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_COUNT_ALL =
      Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*([\"'])countAll\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_UPDATE =
      Pattern.compile("<update\\b[^>]*\\bid\\s*=\\s*([\"'])update\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern ID_DELETE_BY_ID =
      Pattern.compile("<(delete|update)\\b[^>]*\\bid\\s*=\\s*([\"'])deleteById\\2", Pattern.CASE_INSENSITIVE);

  /** fallback */
  private static final String DEFAULT_NOW_FUNCTION = "CURRENT_TIMESTAMP";

  /**
   * 내부 Dialect (props.pagination.dialect 우선, AUTO일 때만 dbProductName으로 추론)
   * - UNKNOWN은 "표준 SQL(OFFSET/FETCH, FETCH FIRST)" 기반으로 생성
   */
  private enum Dialect {
    POSTGRES,
    MYSQL,
    MARIADB,
    H2,
    SQLITE,
    SQLSERVER,
    ORACLE,
    UNKNOWN
  }

  public static String build(Class<?> entityClass,
                             String userXmlContent,
                             MybatisEasyProperties props,
                             String dbProductName) {
    try {
      ColumnAnalyzer.TableInfo tableInfo = ColumnAnalyzer.analyzeClass(entityClass);

      String rawTableName = tableInfo.getTableName();
      String resultTypeName = entityClass.getName();

      List<Field> fields = ColumnAnalyzer.getAllFields(entityClass);

      // PK 정책 (ColumnAnalyzer 기준)
      String pkColumnRaw = (tableInfo.getIdColumn() == null || tableInfo.getIdColumn().isBlank())
          ? "id"
          : tableInfo.getIdColumn();
      String pkProperty = (tableInfo.getIdField() == null || tableInfo.getIdField().isBlank())
          ? "id"
          : tableInfo.getIdField();

      Field softDeleteField = fields.stream()
          .filter(f -> f.isAnnotationPresent(SoftDelete.class))
          .findFirst()
          .orElse(null);

      // AutoSql props
      MybatisEasyProperties.AutoSql autoSqlProps = (props != null) ? props.getAutoSql() : null;

      boolean allowEmptySet = autoSqlProps != null
          && autoSqlProps.getUpdate() != null
          && autoSqlProps.getUpdate().isAllowEmptySet();

      boolean quoteIdentifiers = autoSqlProps != null && autoSqlProps.isQuoteIdentifiers();

      // generated key
      MybatisEasyProperties.AutoSql.GeneratedKey gk = (autoSqlProps != null) ? autoSqlProps.getGeneratedKey() : null;
      MybatisEasyProperties.AutoSql.Strategy configured =
          (gk != null) ? gk.getStrategy() : MybatisEasyProperties.AutoSql.Strategy.AUTO;

      String keyColumn = (gk != null && gk.getKeyColumn() != null && !gk.getKeyColumn().isBlank())
          ? gk.getKeyColumn().trim()
          : "id";

      // Pagination props
      MybatisEasyProperties.Pagination pageProps = (props != null) ? props.getPagination() : null;

      Dialect dialect = resolveDialect(dbProductName, pageProps);
      String nowFn = resolveNowFunction(dialect, pageProps);

      // generated key 전략: AUTO면 DB별 보수적 선택
      MybatisEasyProperties.AutoSql.Strategy strategy = resolveStrategy(configured, dialect);

      // quoting helpers
      IdentifierQuoter quoter = new IdentifierQuoter(dialect, quoteIdentifiers);

      String tableName = quoter.table(rawTableName);
      String pkColumn = quoter.column(pkColumnRaw);

      // SELECT * 제거: 분석된 컬럼 리스트 사용 (+ quote 적용)
      String selectColumns = tableInfo.getFieldColumnMap().values().stream()
          .filter(Objects::nonNull)
          .distinct()
          .map(quoter::column)
          .collect(Collectors.joining(", "));
      if (selectColumns.isBlank()) selectColumns = "*";

      StringBuilder sql = new StringBuilder(4096);

      if (!exists(userXmlContent, ID_INSERT)) {
        sql.append(buildInsert(tableName, fields, pkProperty, quoter.column(keyColumn), strategy, quoter, pkProperty));
      }
      if (!exists(userXmlContent, ID_FIND_BY_ID)) {
        sql.append(buildFindById(tableName, selectColumns, pkColumn, pkProperty, resultTypeName, softDeleteField, quoter));
      }

      // findAll: "열어두되" 운영사고 줄이는 정책 지원
      if (!exists(userXmlContent, ID_FIND_ALL)) {
        String findAllSql = buildFindAll(tableName, selectColumns, resultTypeName, softDeleteField, pageProps, dialect, quoter);
        if (findAllSql != null && !findAllSql.isBlank()) {
          sql.append(findAllSql);
        }
      }

      // pagination.enabled일 때만 findPage/countAll 생성
      boolean paginationEnabled = pageProps != null && pageProps.isEnabled();

      if (paginationEnabled && !exists(userXmlContent, ID_FIND_PAGE)) {
        sql.append(buildFindPage(tableName, selectColumns, pkColumn, resultTypeName, softDeleteField, dialect, tableInfo, pageProps, quoter));
      }

      if (paginationEnabled && shouldGenerateCountAll(pageProps) && !exists(userXmlContent, ID_COUNT_ALL)) {
        sql.append(buildCountAll(tableName, softDeleteField, quoter));
      }

      if (!exists(userXmlContent, ID_UPDATE)) {
        sql.append(buildUpdate(tableName, fields, pkColumn, pkProperty, softDeleteField, allowEmptySet, nowFn, quoter));
      }
      if (!exists(userXmlContent, ID_DELETE_BY_ID)) {
        sql.append(buildDeleteById(tableName, pkColumn, pkProperty, softDeleteField, nowFn, quoter));
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

  private static boolean shouldGenerateCountAll(MybatisEasyProperties.Pagination pageProps) {
    return pageProps != null && pageProps.isEnabled();
  }

  /**
   * AUTO는 "운영에서 안 터지게" 보수적으로:
   * - 대부분: JDBC(useGeneratedKeys)
   * - ORACLE: AUTO면 NONE (시퀀스/트리거/RETURNING 등 케이스별로 달라서 자동화가 위험)
   */
  private static MybatisEasyProperties.AutoSql.Strategy resolveStrategy(MybatisEasyProperties.AutoSql.Strategy configured, Dialect dialect) {
    if (configured != null && configured != MybatisEasyProperties.AutoSql.Strategy.AUTO) {
      return configured;
    }
    if (dialect == Dialect.ORACLE) {
      return MybatisEasyProperties.AutoSql.Strategy.NONE;
    }
    return MybatisEasyProperties.AutoSql.Strategy.JDBC;
  }

  private static Dialect resolveDialect(String dbProductName, MybatisEasyProperties.Pagination pageProps) {
    // 사용자가 명시하면 우선
    if (pageProps != null && pageProps.getDialect() != null && pageProps.getDialect() != MybatisEasyProperties.Pagination.Dialect.AUTO) {
      return mapDialect(pageProps.getDialect());
    }

    // AUTO 추론
    String db = normalizeDbName(dbProductName);

    if (db.contains("postgresql") || db.contains("postgres")) return Dialect.POSTGRES;
    if (db.contains("mariadb")) return Dialect.MARIADB;
    if (db.contains("mysql")) return Dialect.MYSQL;
    if (db.contains("microsoft sql server") || db.contains("sql server") || db.contains("mssql")) return Dialect.SQLSERVER;
    if (db.contains("oracle")) return Dialect.ORACLE;
    if (db.equals("h2") || db.contains("h2")) return Dialect.H2;
    if (db.contains("sqlite")) return Dialect.SQLITE;

    return Dialect.UNKNOWN;
  }

  private static Dialect mapDialect(MybatisEasyProperties.Pagination.Dialect d) {
    return switch (d) {
      case POSTGRES -> Dialect.POSTGRES;
      case MYSQL -> Dialect.MYSQL;
      case MARIADB -> Dialect.MARIADB;
      case ORACLE -> Dialect.ORACLE;
      case SQLSERVER -> Dialect.SQLSERVER;
      case H2 -> Dialect.H2;
      case SQLITE -> Dialect.SQLITE;
      case AUTO -> Dialect.UNKNOWN;
    };
  }

  private static String normalizeDbName(String dbProductName) {
    if (dbProductName == null) return "";
    return dbProductName.trim().toLowerCase(Locale.ROOT);
  }

  private static String resolveNowFunction(Dialect dialect, MybatisEasyProperties.Pagination pageProps) {
    if (pageProps != null) {
      String override = pageProps.getNowFunction();
      if (override != null && !override.isBlank()) return override.trim();
    }
    return switch (dialect) {
      case ORACLE -> "SYSTIMESTAMP";
      case SQLSERVER -> "SYSUTCDATETIME()";
      case POSTGRES, MYSQL, MARIADB, H2, SQLITE, UNKNOWN -> DEFAULT_NOW_FUNCTION;
    };
  }

  private static String buildInsert(String tableName,
                                   List<Field> fields,
                                   String pkProperty,
                                   String keyColumnQuoted,
                                   MybatisEasyProperties.AutoSql.Strategy strategy,
                                   IdentifierQuoter quoter,
                                   String keyProperty) {

    List<Field> nonPkFields = fields.stream()
        .filter(f -> !isPkField(f, pkProperty))
        .collect(Collectors.toList());

    String anyNotNullTest = buildAnyNotNullTestByProperty(nonPkFields);

    StringBuilder sb = new StringBuilder();

    if (strategy == MybatisEasyProperties.AutoSql.Strategy.JDBC) {
      sb.append("  <insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"")
          .append(keyProperty)
          .append("\" keyColumn=\"")
          .append(stripQuotes(keyColumnQuoted))
          .append("\">\n");
    } else {
      sb.append("  <insert id=\"insert\">\n");
    }

    sb.append("    <choose>\n")
        .append("      <when test=\"").append(anyNotNullTest).append("\">\n")
        .append("        INSERT INTO ").append(tableName).append("\n")
        .append("        <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");

    for (Field f : nonPkFields) {
      String col = quoter.column(ColumnAnalyzer.getColumnName(f));
      String prop = f.getName(); // ✅ DTO/VO 공통: entity field name
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

  private static String stripQuotes(String maybeQuoted) {
    if (maybeQuoted == null) return null;
    // keyColumn in MyBatis attribute should be raw column name, not quoted
    return maybeQuoted.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
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
                                      Field softDeleteField,
                                      IdentifierQuoter quoter) {
    StringBuilder sb = new StringBuilder();

    sb.append("  <select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
        .append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n")
        .append("    WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n");

    if (softDeleteField != null) {
      String sdCol = quoter.column(ColumnAnalyzer.getColumnName(softDeleteField));
      sb.append("    AND ").append(sdCol).append(" IS NULL\n");
    }

    sb.append("  </select>\n\n");
    return sb.toString();
  }

  private static String buildFindAll(String tableName,
                                     String selectColumns,
                                     String resultTypeName,
                                     Field softDeleteField,
                                     MybatisEasyProperties.Pagination pageProps,
                                     Dialect dialect,
                                     IdentifierQuoter quoter) {

    MybatisEasyProperties.Pagination.FindAll.Policy policy =
        (pageProps != null && pageProps.getFindAll() != null && pageProps.getFindAll().getPolicy() != null)
            ? pageProps.getFindAll().getPolicy()
            : MybatisEasyProperties.Pagination.FindAll.Policy.NONE;

    if (policy == MybatisEasyProperties.Pagination.FindAll.Policy.DISABLE) {
      return "";
    }

    int cap = (pageProps != null && pageProps.getFindAll() != null) ? pageProps.getFindAll().getCap() : 1000;
    if (cap <= 0) cap = 1000;

    StringBuilder sb = new StringBuilder();
    sb.append("  <select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n");

    boolean needWhereSoftDelete = (softDeleteField != null);
    String sdCol = needWhereSoftDelete ? quoter.column(ColumnAnalyzer.getColumnName(softDeleteField)) : null;

    // base
    if (policy == MybatisEasyProperties.Pagination.FindAll.Policy.CAP) {
      switch (dialect) {
        case SQLSERVER -> sb.append("    SELECT TOP (").append(cap).append(") ").append(selectColumns).append(" FROM ").append(tableName).append("\n");
        case ORACLE -> {
          sb.append("    SELECT ").append(selectColumns).append(" FROM (\n")
              .append("      SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n");
          if (needWhereSoftDelete) sb.append("      WHERE ").append(sdCol).append(" IS NULL\n");
          sb.append("    )\n")
              .append("    WHERE ROWNUM <= ").append(cap).append("\n")
              .append("  </select>\n\n");
          return sb.toString();
        }
        case MYSQL, MARIADB -> sb.append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n");
        case POSTGRES, H2, SQLITE, UNKNOWN -> sb.append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n");
      }
    } else {
      sb.append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n");
    }

    if (needWhereSoftDelete) {
      sb.append("    WHERE ").append(sdCol).append(" IS NULL\n");
    }

    if (policy == MybatisEasyProperties.Pagination.FindAll.Policy.CAP) {
      switch (dialect) {
        case MYSQL, MARIADB -> sb.append("    LIMIT ").append(cap).append("\n");
        case POSTGRES, H2, SQLITE, UNKNOWN -> sb.append("    FETCH FIRST ").append(cap).append(" ROWS ONLY\n"); // ✅ ANSI
        case SQLSERVER, ORACLE -> {
          // already handled
        }
      }
    }

    sb.append("  </select>\n\n");
    return sb.toString();
  }

  private static String buildFindPage(String tableName,
                                      String selectColumns,
                                      String pkColumn,
                                      String resultTypeName,
                                      Field softDeleteField,
                                      Dialect dialect,
                                      ColumnAnalyzer.TableInfo tableInfo,
                                      MybatisEasyProperties.Pagination pageProps,
                                      IdentifierQuoter quoter) {

    String orderBy = buildOrderBy(pkColumn, tableInfo, pageProps, quoter);

    StringBuilder baseSelect = new StringBuilder();
    baseSelect.append("    SELECT ").append(selectColumns).append(" FROM ").append(tableName).append("\n");

    if (softDeleteField != null) {
      String sdCol = quoter.column(ColumnAnalyzer.getColumnName(softDeleteField));
      baseSelect.append("    WHERE ").append(sdCol).append(" IS NULL\n");
    }

    int max = (pageProps != null) ? pageProps.getMaxPageSize() : 200;
    if (max <= 0) max = 200;

    StringBuilder sb = new StringBuilder();
    sb.append("  <select id=\"findPage\" resultType=\"").append(resultTypeName).append("\">\n")
      .append("    <bind name=\"__limit\" value=\"limit > ").append(max).append(" ? ").append(max).append(" : limit\"/>\n");

    switch (dialect) {
      case MYSQL, MARIADB -> {
        sb.append(baseSelect);
        sb.append(orderBy);
        sb.append("    LIMIT #{__limit} OFFSET #{offset}\n");
      }
      case SQLSERVER -> {
        sb.append(baseSelect);
        sb.append(orderBy);
        sb.append("    OFFSET #{offset} ROWS FETCH NEXT #{__limit} ROWS ONLY\n");
      }
      case ORACLE -> {
        sb.append("    SELECT ").append(selectColumns).append(" FROM (\n")
            .append("      SELECT inner_q.*, ROWNUM rn FROM (\n")
            .append(baseSelect)
            .append(orderBy)
            .append("      ) inner_q\n")
            .append("      WHERE ROWNUM <= (#{offset} + #{__limit})\n")
            .append("    )\n")
            .append("    WHERE rn > #{offset}\n");
      }
      case POSTGRES, H2, SQLITE, UNKNOWN -> {
        // ANSI SQL:2008 페이징 (UNKNOWN 포함)
        sb.append(baseSelect);
        sb.append(orderBy);
        sb.append("    OFFSET #{offset} ROWS FETCH NEXT #{__limit} ROWS ONLY\n");
      }
    }

    sb.append("  </select>\n\n");
    return sb.toString();
  }

  private static String buildOrderBy(String pkColumn,
                                     ColumnAnalyzer.TableInfo tableInfo,
                                     MybatisEasyProperties.Pagination pageProps,
                                     IdentifierQuoter quoter) {

    MybatisEasyProperties.Pagination.DefaultOrder.Mode mode =
        (pageProps != null && pageProps.getDefaultOrder() != null && pageProps.getDefaultOrder().getMode() != null)
            ? pageProps.getDefaultOrder().getMode()
            : MybatisEasyProperties.Pagination.DefaultOrder.Mode.AUTO;

    MybatisEasyProperties.Pagination.DefaultOrder.Direction dir =
        (pageProps != null && pageProps.getDefaultOrder() != null && pageProps.getDefaultOrder().getDirection() != null)
            ? pageProps.getDefaultOrder().getDirection()
            : MybatisEasyProperties.Pagination.DefaultOrder.Direction.DESC;

    String direction = (dir == MybatisEasyProperties.Pagination.DefaultOrder.Direction.ASC) ? "ASC" : "DESC";

    if (mode == MybatisEasyProperties.Pagination.DefaultOrder.Mode.NONE) {
      // ORDER BY 자체를 제거 (DB 호환성/의미 측면에서 안전)
      return "";
    }

    Set<String> cols = new HashSet<>();
    if (tableInfo != null && tableInfo.getFieldColumnMap() != null) {
      for (String c : tableInfo.getFieldColumnMap().values()) {
        if (c != null) cols.add(c.toLowerCase(Locale.ROOT));
      }
    }

    String resolvedRaw;
    if (mode == MybatisEasyProperties.Pagination.DefaultOrder.Mode.CREATED_AT) {
      resolvedRaw = cols.contains("created_at") ? "created_at" : null;
    } else if (mode == MybatisEasyProperties.Pagination.DefaultOrder.Mode.UPDATED_AT) {
      resolvedRaw = cols.contains("updated_at") ? "updated_at" : null;
    } else if (mode == MybatisEasyProperties.Pagination.DefaultOrder.Mode.PK) {
      resolvedRaw = (pkColumn != null && !pkColumn.isBlank()) ? stripQuotes(pkColumn) : null;
    } else {
      if (cols.contains("created_at")) resolvedRaw = "created_at";
      else if (cols.contains("updated_at")) resolvedRaw = "updated_at";
      else resolvedRaw = (pkColumn != null && !pkColumn.isBlank()) ? stripQuotes(pkColumn) : null;
    }

    if (resolvedRaw == null || resolvedRaw.isBlank()) {
      // ORDER BY 후보가 없으면 생략 (무리해서 ORDER BY 1 같은 거 하지 않음)
      return "";
    }

    return "    ORDER BY " + quoter.column(resolvedRaw) + " " + direction + "\n";
  }

  private static String buildCountAll(String tableName, Field softDeleteField, IdentifierQuoter quoter) {
    StringBuilder sb = new StringBuilder();
    sb.append("  <select id=\"countAll\" resultType=\"long\">\n")
        .append("    SELECT COUNT(*) FROM ").append(tableName).append("\n");

    if (softDeleteField != null) {
      String sdCol = quoter.column(ColumnAnalyzer.getColumnName(softDeleteField));
      sb.append("    WHERE ").append(sdCol).append(" IS NULL\n");
    }

    sb.append("  </select>\n\n");
    return sb.toString();
  }

  private static String buildUpdate(String tableName,
                                    List<Field> fields,
                                    String pkColumn,
                                    String pkProperty,
                                    Field softDeleteField,
                                    boolean allowEmptySet,
                                    String nowFn,
                                    IdentifierQuoter quoter) {

    boolean hasUpdatedAt = fields.stream()
        .map(ColumnAnalyzer::getColumnName)
        .filter(Objects::nonNull)
        .anyMatch(c -> "updated_at".equalsIgnoreCase(c));

    List<Field> updatableFields = fields.stream()
        .filter(f -> !isPkField(f, pkProperty))
        .filter(f -> {
          String c = ColumnAnalyzer.getColumnName(f);
          return c == null || !"updated_at".equalsIgnoreCase(c);
        })
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
      String col = quoter.column(ColumnAnalyzer.getColumnName(f));
      String prop = f.getName(); // ✅ entity field name
      sb.append("          <if test=\"").append(prop).append(" != null\">")
          .append(col).append(" = #{").append(prop).append("},</if>\n");
    }

    if (hasUpdatedAt) {
      sb.append("          ").append(quoter.column("updated_at")).append(" = ").append(nowFn).append(",\n");
    }

    sb.append("        </set>\n")
        .append("        WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n");

    if (softDeleteField != null) {
      String sdCol = quoter.column(ColumnAnalyzer.getColumnName(softDeleteField));
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
                                        Field softDeleteField,
                                        String nowFn,
                                        IdentifierQuoter quoter) {
    StringBuilder sb = new StringBuilder();

    if (softDeleteField != null) {
      String sdCol = quoter.column(ColumnAnalyzer.getColumnName(softDeleteField));
      sb.append("  <update id=\"deleteById\">\n")
          .append("    UPDATE ").append(tableName).append("\n")
          .append("    SET ").append(sdCol).append(" = ").append(nowFn).append("\n")
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

    if (pkProperty.equals(f.getName())) return true;

    return f.isAnnotationPresent(Id.class) && "id".equals(pkProperty);
  }

  /**
   * Dialect-aware identifier quoting
   * - schema.table, a.b, table.* 같은 dot-path를 segment 별로 quote
   */
  private static final class IdentifierQuoter {
    private final Dialect dialect;
    private final boolean enabled;

    private IdentifierQuoter(Dialect dialect, boolean enabled) {
      this.dialect = (dialect == null) ? Dialect.UNKNOWN : dialect;
      this.enabled = enabled;
    }

    String table(String name) {
      return quotePath(name);
    }

    String column(String name) {
      return quotePath(name);
    }

    private String quotePath(String raw) {
      if (!enabled) return raw;
      if (raw == null || raw.isBlank()) return raw;

      String s = raw.trim();
      if ("*".equals(s)) return s;

      // 이미 전체가 quote인 경우는 그대로 둠 (단일 식별자)
      if (isFullyQuoted(s)) return s;

      // dot-path 분해 (schema.table / a.b / table.*)
      if (s.indexOf('.') >= 0) {
        String[] parts = s.split("\\.");
        StringBuilder out = new StringBuilder(s.length() + parts.length * 2);

        for (int i = 0; i < parts.length; i++) {
          if (i > 0) out.append('.');
          String part = parts[i] == null ? "" : parts[i].trim();
          if (part.isEmpty()) {
            // 비정상 케이스: ".." 같은 입력은 그대로 반영
            continue;
          }
          if ("*".equals(part)) {
            out.append('*');
            continue;
          }
          if (isFullyQuoted(part)) {
            out.append(part);
            continue;
          }
          out.append(quoteSingle(part));
        }
        return out.toString();
      }

      return quoteSingle(s);
    }

    private boolean isFullyQuoted(String s) {
      if (s == null || s.length() < 2) return false;
      return (s.startsWith("`") && s.endsWith("`"))
          || (s.startsWith("\"") && s.endsWith("\""))
          || (s.startsWith("[") && s.endsWith("]"));
    }

    private String quoteSingle(String token) {
      if (token == null || token.isBlank()) return token;

      // 이미 보호된 형태면 유지
      if (isFullyQuoted(token)) return token;

      return switch (dialect) {
        case MYSQL, MARIADB -> "`" + token + "`";
        case SQLSERVER -> "[" + token + "]";
        case POSTGRES, ORACLE, H2, SQLITE, UNKNOWN -> "\"" + token + "\"";
      };
    }
  }

}
