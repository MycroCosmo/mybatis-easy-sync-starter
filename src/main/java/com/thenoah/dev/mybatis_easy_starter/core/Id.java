package com.thenoah.dev.mybatis_easy_starter.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)          // 필드 위에만 붙일 수 있음
@Retention(RetentionPolicy.RUNTIME) // 실행 중(Runtime)에도 정보가 유지됨
public @interface Id {              // @ 기호가 붙은 interface임을 확인하세요!
}