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

  private static final Map<Class<?>, TableInfo> TABLE_INFO_CACHE = new ConcurrentHashMap<>();
  private static final Map<Class<?>, List<Field>> ALL_FIELDS_CACHE = new ConcurrentHashMap<>();

  /**
   * 캐시 클리어
   * - NamingStrategy 전역 변경(혹은 컨텍스트 재시작) 시 캐시 오염 방지용
   */
  public static void clearCache() {
    TABLE_INFO_CACHE.clear();
    ALL_FIELDS_CACHE.clear();
  }

  public static TableInfo analyze(Object entity) {
    if (entity == null) {
      throw new IllegalArgumentException("entity must not be null");
    }
    return analyzeClass(entity.getClass());
  }

  public static TableInfo analyzeClass(Class<?> clazz) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz must not be null");
    }
    return TABLE_INFO_CACHE.computeIfAbsent(clazz, ColumnAnalyzer::extractTableInfo);
  }

  private static TableInfo extractTableInfo(Class<?> clazz) {
    NamingStrategy naming = NamingStrategyHolder.get();

    String tableName = resolveTableName(clazz, naming);

    List<Field> allFields = getAllFields(clazz);

    Map<String, String> fieldColumnMap = new LinkedHashMap<>();

    String idColumn = null;
    String idField = null;

    for (Field field : allFields) {
      if (isSkippable(field)) continue;

      String fieldName = field.getName();
      String columnName = getColumnName(field, naming);

      fieldColumnMap.put(fieldName, columnName);

      if (field.isAnnotationPresent(Id.class)) {
        if (idField == null) {
          idField = fieldName;
          idColumn = columnName;
        }
      }
    }

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
    return naming.tableName(clazz);
  }

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

  public static String getColumnName(Field field) {
    return getColumnName(field, NamingStrategyHolder.get());
  }

  static String getColumnName(Field field, NamingStrategy naming) {
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
