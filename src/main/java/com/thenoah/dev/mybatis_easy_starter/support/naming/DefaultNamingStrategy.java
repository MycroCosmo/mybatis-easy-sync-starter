package com.thenoah.dev.mybatis_easy_starter.support.naming;

public class DefaultNamingStrategy implements NamingStrategy {
  @Override
  public String tableName(Class<?> type) {
    String simple = type.getSimpleName().replaceAll("(Dto|VO|Entity)$", "");
    return camelToSnake(simple);
  }

  @Override
  public String columnName(String fieldName) {
    return camelToSnake(fieldName);
  }

  private String camelToSnake(String str) {
    return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
  }
}
