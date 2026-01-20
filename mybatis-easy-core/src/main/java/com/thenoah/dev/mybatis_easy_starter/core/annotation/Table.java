package com.thenoah.dev.mybatis_easy_starter.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)           // 클래스(TYPE) 위에만 붙일 수 있음
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {
    String name(); // 테이블 이름을 입력받을 수 있는 속성
}