package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.CascadeDelete;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;
import com.thenoah.dev.mybatis_easy_starter.support.ColumnAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MyBatis 가상 XML에 주입될 CRUD SQL을 생성하는 빌더 클래스입니다.
 * DTO 사용 시에도 값이 존재하는 필드만 동적으로 처리하도록 <if>문을 활용합니다.
 */
public class AutoSqlBuilder {
  private static final Logger log = LoggerFactory.getLogger(AutoSqlBuilder.class);

  public static String build(Class<?> entityClass, String userContent) {
    try {
      // 1. 테이블명 결정 (@Table 어노테이션 우선, 없으면 클래스명 기반 유추)
      String tableName = entityClass.isAnnotationPresent(Table.class)
              ? entityClass.getAnnotation(Table.class).name()
              : camelToSnake(entityClass.getSimpleName().replace("VO", "").replace("Dto", ""));

      String resultTypeName = entityClass.getName();
      List<Field> fields = ColumnAnalyzer.getAllFields(entityClass);

      // 2. 특수 목적 필드 수집 (PK, SoftDelete, CascadeDelete)
      Field pkFieldObj = null;
      Field softDeleteField = null;
      List<CascadeDelete> cascadeDeletes = new ArrayList<>();

      for (Field f : fields) {
        if (f.isAnnotationPresent(Id.class)) {
          pkFieldObj = f;
        }
        if (f.isAnnotationPresent(SoftDelete.class)) {
          softDeleteField = f;
        }
        if (f.isAnnotationPresent(CascadeDelete.class)) {
          cascadeDeletes.add(f.getAnnotation(CascadeDelete.class));
        }
      }

      // PK 정보 확정 (없으면 기본값 id)
      String pkField = (pkFieldObj != null) ? pkFieldObj.getName() : "id";
      String pkColumn = (pkFieldObj != null) ? ColumnAnalyzer.getColumnName(pkFieldObj) : "id";

      StringBuilder sql = new StringBuilder(2048);

      // [INSERT] - <trim>과 <if>를 사용하여 DTO에 있는 필드만 선택적 삽입
      if (!userContent.contains("id=\"insert\"")) {
        sql.append("  <insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"").append(pkField).append("\">\n")
                .append("    INSERT INTO ").append(tableName).append("\n")
                .append("    <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");

        for (Field f : fields) {
          String col = ColumnAnalyzer.getColumnName(f);
          sql.append("      <if test=\"").append(f.getName()).append(" != null\">").append(col).append(", </if>\n");
        }

        sql.append("    </trim>\n")
                .append("    <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">\n");

        for (Field f : fields) {
          sql.append("      <if test=\"").append(f.getName()).append(" != null\">#{").append(f.getName()).append("}, </if>\n");
        }

        sql.append("    </trim>\n")
                .append("  </insert>\n");
      }

      // [findById]
      if (!userContent.contains("id=\"findById\"")) {
        sql.append("  <select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
                .append("    SELECT * FROM ").append(tableName)
                .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                .append("  </select>\n");
      }

      // [findAll] - SoftDelete 반영
      if (!userContent.contains("id=\"findAll\"")) {
        sql.append("  <select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n")
                .append("    SELECT * FROM ").append(tableName).append("\n");

        if (softDeleteField != null) {
          String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
          SoftDelete sd = softDeleteField.getAnnotation(SoftDelete.class);
          Class<?> ft = softDeleteField.getType();

          if (ft.equals(LocalDateTime.class) || ft.equals(Date.class) ||
                  (ft.equals(String.class) && "true".equalsIgnoreCase(sd.deletedValue()))) {
            sql.append("    WHERE (").append(sdCol).append(" IS NULL OR ").append(sdCol).append(" = '')\n");
          } else {
            sql.append("    WHERE ").append(sdCol).append(" = ").append(sd.notDeletedValue()).append("\n");
          }
        }
        sql.append("  </select>\n");
      }

      // [UPDATE] - <set>과 <if>를 활용하여 DTO의 부분 수정(Partial Update) 지원
      if (!userContent.contains("id=\"update\"")) {
        sql.append("  <update id=\"update\">\n")
                .append("    UPDATE ").append(tableName).append("\n")
                .append("    <set>\n");

        for (Field f : fields) {
          if (f.equals(pkFieldObj)) continue; // PK는 수정 제외
          String fieldName = f.getName();
          String columnName = ColumnAnalyzer.getColumnName(f);
          sql.append("      <if test=\"").append(fieldName).append(" != null\">")
                  .append(columnName).append(" = #{").append(fieldName).append("}, </if>\n");
        }

        sql.append("    </set>\n")
                .append("    WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                .append("  </update>\n");
      }

      // [deleteById] - SoftDelete & CascadeDelete 통합 처리
      if (!userContent.contains("id=\"deleteById\"")) {
        String tag = (softDeleteField != null) ? "update" : "delete";
        sql.append("  <").append(tag).append(" id=\"deleteById\">\n");

        // Cascade Delete 처리 (연관 테이블 먼저 삭제)
        for (CascadeDelete cd : cascadeDeletes) {
          sql.append("    DELETE FROM ").append(cd.table())
                  .append(" WHERE ").append(cd.foreignKey()).append(" = #{").append(pkField).append("};\n");
        }

        if (softDeleteField != null) {
          // Soft Delete 처리
          String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
          SoftDelete sd = softDeleteField.getAnnotation(SoftDelete.class);
          Class<?> ft = softDeleteField.getType();

          String updateVal = (ft.equals(LocalDateTime.class) || ft.equals(Date.class)) ? "NOW()" :
                  (ft.equals(String.class) && "true".equalsIgnoreCase(sd.deletedValue())) ? "DATE_FORMAT(NOW(), '%Y%m%d%H%i%s')" :
                          sd.deletedValue();

          sql.append("    UPDATE ").append(tableName)
                  .append(" SET ").append(sdCol).append(" = ").append(updateVal)
                  .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n");
        } else {
          // Hard Delete 처리
          sql.append("    DELETE FROM ").append(tableName)
                  .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n");
        }
        sql.append("  </").append(tag).append(">\n");
      }

      return sql.toString();
    } catch (Exception e) {
      log.error("MyBatis-Easy: CRUD generation failed for {}", entityClass.getSimpleName(), e);
      return "";
    }
  }

  private static String camelToSnake(String str) {
    if (str == null) return null;
    return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
  }
}