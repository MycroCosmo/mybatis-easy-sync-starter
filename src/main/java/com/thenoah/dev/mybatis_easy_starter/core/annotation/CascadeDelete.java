package com.thenoah.dev.mybatis_easy_starter.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CascadeDelete {
  String table();          // 자식 테이블 이름
  String foreignKey();     // 자식 테이블의 외래키 컬럼명
}