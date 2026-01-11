package com.thenoah.dev.mybatis_easy_starter.support;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ColumnAnalyzer {

  // 성능을 위해 클래스별 분석 결과를 캐싱
  private static final Map<Class<?>, TableInfo> cache = new ConcurrentHashMap<>();

  /**
   * [핵심 추가] 실제 객체(DTO/VO)를 분석하여 테이블 및 컬럼 정보를 반환합니다.
   */
  public static TableInfo analyze(Object entity) {
    Class<?> clazz = entity.getClass();
    return cache.computeIfAbsent(clazz, ColumnAnalyzer::extractTableInfo);
  }

  private static TableInfo extractTableInfo(Class<?> clazz) {
    // 1. 테이블명 결정 (@Table 우선, 없으면 클래스명 유추)
    String tableName;
    if (clazz.isAnnotationPresent(Table.class)) {
      tableName = clazz.getAnnotation(Table.class).name();
    } else {
      // Dto, VO 접미사 제거 후 Snake Case 변환
      String simpleName = clazz.getSimpleName().replaceAll("(Dto|VO|Entity)$", "");
      tableName = camelToSnake(simpleName);
    }

    // 2. 모든 필드 분석 (부모 클래스 포함)
    List<Field> allFields = getAllFields(clazz);
    Map<String, String> fieldColumnMap = new LinkedHashMap<>();
    String idColumn = null;
    String idField = null;

    for (Field field : allFields) {
      // static 필드나 synthetic 필드 제외
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;

      String columnName = getColumnName(field);
      String fieldName = field.getName();
      fieldColumnMap.put(fieldName, columnName);

      // @Id 어노테이션 확인
      if (field.isAnnotationPresent(Id.class) || fieldName.equalsIgnoreCase("id")) {
        idColumn = columnName;
        idField = fieldName;
      }
    }

    return new TableInfo(tableName, fieldColumnMap, idColumn, idField);
  }

  public static List<Field> getAllFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();
    while (clazz != null && clazz != Object.class) {
      fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
      clazz = clazz.getSuperclass();
    }
    return fields;
  }

  public static String getColumnName(Field field) {
    if (field.isAnnotationPresent(Column.class)) {
      String name = field.getAnnotation(Column.class).name();
      if (!name.isEmpty()) return name;
    }
    return camelToSnake(field.getName());
  }

  private static String camelToSnake(String str) {
    if (str == null) return null;
    return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
  }

  /**
   * 분석 결과를 담는 내부 DTO
   */
  public static class TableInfo {
    private final String tableName;
    private final Map<String, String> fieldColumnMap; // key: fieldName, value: columnName
    private final String idColumn;
    private final String idField;

    public TableInfo(String tableName, Map<String, String> fieldColumnMap, String idColumn, String idField) {
      this.tableName = tableName;
      this.fieldColumnMap = fieldColumnMap;
      this.idColumn = idColumn;
      this.idField = idField;
    }

    public String getTableName() { return tableName; }
    public Map<String, String> getFieldColumnMap() { return fieldColumnMap; }
    public String getIdColumn() { return idColumn; }
    public String getIdField() { return idField; }
  }
}
