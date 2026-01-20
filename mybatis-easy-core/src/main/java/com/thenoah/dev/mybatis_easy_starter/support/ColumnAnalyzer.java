package com.thenoah.dev.mybatis_easy_starter.support;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;
import com.thenoah.dev.mybatis_easy_starter.support.naming.NamingStrategy;
import com.thenoah.dev.mybatis_easy_starter.support.naming.NamingStrategyHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ColumnAnalyzer {

  private ColumnAnalyzer() {}

  // 클래스별 테이블/컬럼 분석 결과 캐시
  private static final Map<Class<?>, TableInfo> TABLE_INFO_CACHE = new ConcurrentHashMap<>();

  // 클래스별 전체 필드 캐시 (부모 포함)
  private static final Map<Class<?>, List<Field>> ALL_FIELDS_CACHE = new ConcurrentHashMap<>();

  /**
   * 객체 기반 분석
   */
  public static TableInfo analyze(Object entity) {
    if (entity == null) {
      throw new IllegalArgumentException("entity must not be null");
    }
    return analyzeClass(entity.getClass());
  }

  /**
   * 클래스 기반 분석
   */
  public static TableInfo analyzeClass(Class<?> clazz) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz must not be null");
    }
    return TABLE_INFO_CACHE.computeIfAbsent(clazz, ColumnAnalyzer::extractTableInfo);
  }

  private static TableInfo extractTableInfo(Class<?> clazz) {
    NamingStrategy naming = NamingStrategyHolder.get();

    // 1) 테이블명 결정: @Table 우선, 없으면 NamingStrategy
    String tableName = resolveTableName(clazz, naming);

    // 2) 모든 필드 분석 (부모 클래스 포함)
    List<Field> allFields = getAllFields(clazz);

    Map<String, String> fieldColumnMap = new LinkedHashMap<>();

    // id 우선순위: @Id가 붙은 필드가 1순위, 없으면 fieldName == "id"
    String idColumn = null;
    String idField = null;

    for (Field field : allFields) {
      if (isSkippable(field)) continue;

      String fieldName = field.getName();
      String columnName = getColumnName(field, naming);

      fieldColumnMap.put(fieldName, columnName);

      if (field.isAnnotationPresent(Id.class)) {
        // @Id는 무조건 최우선
        if (idField == null) {
          idField = fieldName;
          idColumn = columnName;
        }
      }
    }

    // @Id가 하나도 없으면 id라는 이름을 fallback으로 잡음
    if (idField == null) {
      for (Field field : allFields) {
        if (isSkippable(field)) continue;
        if ("id".equalsIgnoreCase(field.getName())) {
          idField = field.getName();
          idColumn = getColumnName(field, naming);
          break;
        }
      }
    }

    return new TableInfo(tableName, fieldColumnMap, idColumn, idField);
  }

  private static String resolveTableName(Class<?> clazz, NamingStrategy naming) {
    if (clazz.isAnnotationPresent(Table.class)) {
      String name = clazz.getAnnotation(Table.class).name();
      if (name != null && !name.isBlank()) {
        return name;
      }
    }
    // 없으면 전략 사용 (기본 전략은 기존 camelToSnake 동작과 동일)
    return naming.tableName(clazz);
  }

  /**
   * 부모 클래스 포함 전체 필드 반환 (캐시 적용)
   */
  public static List<Field> getAllFields(Class<?> clazz) {
    return ALL_FIELDS_CACHE.computeIfAbsent(clazz, ColumnAnalyzer::loadAllFields);
  }

  private static List<Field> loadAllFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();
    Class<?> cur = clazz;

    while (cur != null && cur != Object.class) {
      fields.addAll(Arrays.asList(cur.getDeclaredFields()));
      cur = cur.getSuperclass();
    }

    return Collections.unmodifiableList(fields);
  }

  /**
   * 필드 -> 컬럼명
   * - @Column(name)이 있으면 그 값 (우선)
   * - 없으면 NamingStrategy
   */
  public static String getColumnName(Field field) {
    return getColumnName(field, NamingStrategyHolder.get());
  }

  private static String getColumnName(Field field, NamingStrategy naming) {
    if (field.isAnnotationPresent(Column.class)) {
      String name = field.getAnnotation(Column.class).name();
      if (name != null && !name.isBlank()) {
        return name;
      }
    }
    return naming.columnName(field.getName());
  }

  private static boolean isSkippable(Field field) {
    return Modifier.isStatic(field.getModifiers()) || field.isSynthetic();
  }

  /**
   * 분석 결과 DTO
   */
  public static final class TableInfo {
    private final String tableName;
    private final Map<String, String> fieldColumnMap;
    private final String idColumn;
    private final String idField;

    public TableInfo(String tableName, Map<String, String> fieldColumnMap, String idColumn, String idField) {
      this.tableName = tableName;
      this.fieldColumnMap = Collections.unmodifiableMap(new LinkedHashMap<>(fieldColumnMap));
      this.idColumn = idColumn;
      this.idField = idField;
    }

    public String getTableName() { return tableName; }
    public Map<String, String> getFieldColumnMap() { return fieldColumnMap; }
    public String getIdColumn() { return idColumn; }
    public String getIdField() { return idField; }
  }
}
