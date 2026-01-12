package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;
import com.thenoah.dev.mybatis_easy_starter.support.ColumnAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

public class AutoSqlBuilder {
    private static final Logger log = LoggerFactory.getLogger(AutoSqlBuilder.class);

    public static String build(Class<?> entityClass, String userContent) {
        try {
            String tableName = entityClass.isAnnotationPresent(Table.class)
                    ? entityClass.getAnnotation(Table.class).name()
                    : camelToSnake(entityClass.getSimpleName().replace("VO", "").replace("Dto", ""));
            
            String resultTypeName = entityClass.getName();
            List<Field> fields = ColumnAnalyzer.getAllFields(entityClass);
            
            Field pkField = fields.stream().filter(f -> f.isAnnotationPresent(Id.class)).findFirst().orElse(null);
            Field softDeleteField = fields.stream().filter(f -> f.isAnnotationPresent(SoftDelete.class)).findFirst().orElse(null);
            
            String pkColumn = (pkField != null) ? ColumnAnalyzer.getColumnName(pkField) : "id";
            String pkProperty = (pkField != null) ? pkField.getName() : "id";

            StringBuilder sql = new StringBuilder();

            // [INSERT]
            if (!userContent.contains("id=\"insert\"")) {
                sql.append("  <insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"").append(pkProperty).append("\">\n")
                   .append("    INSERT INTO ").append(tableName).append("\n")
                   .append("    <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
                for (Field f : fields) {
                    String dbCol = ColumnAnalyzer.getColumnName(f);
                    sql.append("      <if test=\"").append(dbCol).append(" != null\">").append(dbCol).append(", </if>\n");
                }
                sql.append("    </trim>\n")
                   .append("    <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">\n");
                for (Field f : fields) {
                    String dbCol = ColumnAnalyzer.getColumnName(f);
                    sql.append("      <if test=\"").append(dbCol).append(" != null\">#{").append(dbCol).append("}, </if>\n");
                }
                sql.append("    </trim>\n  </insert>\n");
            }

            // [findById]
            if (!userContent.contains("id=\"findById\"")) {
                sql.append("  <select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
                   .append("    SELECT * FROM ").append(tableName).append(" WHERE ").append(pkColumn).append(" = #{id}\n");
                if (softDeleteField != null) {
                    sql.append("    AND ").append(ColumnAnalyzer.getColumnName(softDeleteField)).append(" IS NULL\n");
                }
                sql.append("  </select>\n");
            }

            // [findAll]
            if (!userContent.contains("id=\"findAll\"")) {
                sql.append("  <select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n")
                   .append("    SELECT * FROM ").append(tableName).append("\n");
                if (softDeleteField != null) {
                    String sdCol = ColumnAnalyzer.getColumnName(softDeleteField);
                    sql.append("    WHERE ").append(sdCol).append(" IS NULL\n");
                }
                sql.append("  </select>\n");
            }

            // [UPDATE]
            if (!userContent.contains("id=\"update\"")) {
                sql.append("  <update id=\"update\">\n")
                   .append("    UPDATE ").append(tableName).append("\n")
                   .append("    <set>\n");
                
                for (Field f : fields) {
                    if (f.isAnnotationPresent(Id.class) || f.getName().equals("id")) continue;
                    String dbCol = ColumnAnalyzer.getColumnName(f);
                    if (dbCol.equals("updated_at")) {
                        sql.append("      ").append(dbCol).append(" = NOW(), \n");
                        continue;
                    }
                    sql.append("      <if test=\"").append(dbCol).append(" != null\">")
                       .append(dbCol).append(" = #{").append(dbCol).append("}, </if>\n");
                }
                
                sql.append("    </set>\n")
                   .append("    WHERE ").append(pkColumn).append(" = #{").append(pkProperty).append("}\n");
                
                if (softDeleteField != null) {
                    sql.append("    AND ").append(ColumnAnalyzer.getColumnName(softDeleteField)).append(" IS NULL\n");
                }
                sql.append("  </update>\n");
            }

            // [deleteById]
            if (!userContent.contains("id=\"deleteById\"")) {
                if (softDeleteField != null) {
                    sql.append("  <update id=\"deleteById\">\n")
                       .append("    UPDATE ").append(tableName).append(" SET ").append(ColumnAnalyzer.getColumnName(softDeleteField)).append(" = NOW()\n")
                       .append("    WHERE ").append(pkColumn).append(" = #{id}\n")
                       .append("  </update>\n");
                } else {
                    sql.append("  <delete id=\"deleteById\">\n")
                       .append("    DELETE FROM ").append(tableName).append(" WHERE ").append(pkColumn).append(" = #{id}\n")
                       .append("  </delete>\n");
                }
            }
            return sql.toString();
        } catch (Exception e) {
            log.error("AutoSqlBuilder failed", e);
            return "";
        }
    }

    private static String camelToSnake(String str) {
        return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }
}