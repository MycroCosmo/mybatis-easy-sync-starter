package com.thenoah.dev.mybatis_easy_starter.support;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityParser {

    private static final Logger log = LoggerFactory.getLogger(EntityParser.class);

    private EntityParser() {}

    /**
     * DTO/VO에 @Column이 하나라도 있는지 확인
     * - Interceptor에서 "변환이 필요한 경우에만" Map 변환하도록 판단 기준으로 사용
     */
    public static boolean isColumnAnnotationPresent(Class<?> clazz) {
        if (clazz == null) return false;
        return ColumnAnalyzer.getAllFields(clazz).stream()
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .anyMatch(f -> f.isAnnotationPresent(Column.class));
    }

    /**
     * 객체 -> Map 변환
     * - key는 "프로퍼티명(필드명)"
     * - value는 null 제외
     *
     * 주의:
     * - AutoSqlBuilder가 #{fieldName} 형태로 생성되므로, Map의 key도 fieldName이어야 함
     */
    public static Map<String, Object> toMap(Object obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;

        List<Field> fields = ColumnAnalyzer.getAllFields(obj.getClass());
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;

            try {
                if (!field.canAccess(obj)) {
                    field.setAccessible(true);
                }
                Object value = field.get(obj);
                if (value != null) {
                    // 핵심: 컬럼명이 아니라 프로퍼티명(필드명)
                    map.put(field.getName(), value);
                }
            } catch (Exception e) {
                log.warn("EntityParser: field access failed. field={}", field.getName(), e);
            }
        }
        return map;
    }
}
