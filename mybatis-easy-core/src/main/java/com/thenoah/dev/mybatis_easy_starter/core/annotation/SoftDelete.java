package com.thenoah.dev.mybatis_easy_starter.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SoftDelete {
  // 특정 값을 사용할 경우 (플래그 방식)
  String deletedValue() default "";
  String notDeletedValue() default "";

  // 아무 설정이 없으면 필드 타입이 날짜형일 때 자동으로 '현재 시간'과 'NULL'로 처리
}