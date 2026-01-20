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

    public static boolean isColumnAnnotationPresent(Class<?> clazz) {
        if (clazz == null) return false;
        return ColumnAnalyzer.getAllFields(clazz).stream()
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .anyMatch(f -> f.isAnnotationPresent(Column.class));
    }

    /**
     * ✅ @Column이 붙은 필드만 Map에 포함한다.
     */
    public static Map<String, Object> toMap(Object obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;

        List<Field> fields = ColumnAnalyzer.getAllFields(obj.getClass());
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            if (!field.isAnnotationPresent(Column.class)) continue;

            try {
                if (!field.canAccess(obj)) {
                    field.setAccessible(true);
                }
                Object value = field.get(obj);
                if (value != null) {
                    map.put(field.getName(), value);
                }
            } catch (Exception e) {
                log.warn("EntityParser: field access failed. field={}", field.getName(), e);
            }
        }
        return map;
    }
}
