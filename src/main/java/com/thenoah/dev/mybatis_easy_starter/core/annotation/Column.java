package com.thenoah.dev.mybatis_easy_starter.core.annotation;

import java.lang.annotation.*;

/**
 * 필드명과 DB 컬럼명이 일치하지 않을 때 명시적으로 매핑하기 위한 어노테이션
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {
  String name(); // DB 컬럼명을 지정
}