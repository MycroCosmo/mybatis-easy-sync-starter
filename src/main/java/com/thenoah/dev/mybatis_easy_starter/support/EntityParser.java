package com.thenoah.dev.mybatis_easy_starter.support;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntityParser {
    private static final Logger log = LoggerFactory.getLogger(EntityParser.class);

    public static boolean isColumnAnnotationPresent(Class<?> clazz) {
        return ColumnAnalyzer.getAllFields(clazz).stream()
                .anyMatch(f -> f.isAnnotationPresent(Column.class));
    }

    public static Map<String, Object> toMap(Object obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;

        List<Field> fields = ColumnAnalyzer.getAllFields(obj.getClass());
        for (Field field : fields) {
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                if (value != null) {
                    // 리더님의 요구사항: @Column 우선, 없으면 SnakeCase 변환 이름 사용
                    String key = ColumnAnalyzer.getColumnName(field);
                    map.put(key, value);
                }
            } catch (IllegalAccessException e) {
                log.error("Field access error: {}", field.getName());
            }
        }
        return map;
    }
}