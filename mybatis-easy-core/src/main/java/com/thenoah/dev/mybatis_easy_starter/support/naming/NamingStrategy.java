package com.thenoah.dev.mybatis_easy_starter.support.naming;

public interface NamingStrategy {
  String tableName(Class<?> type);
  String columnName(String fieldName);
}
