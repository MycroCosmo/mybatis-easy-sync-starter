package com.thenoah.dev.mybatis_easy_starter.support;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityParser {

    private static final Logger log = LoggerFactory.getLogger(EntityParser.class);

    private EntityParser() {}

    // entityClass별 column->entityField 역매핑 캐시
    private static final Map<Class<?>, Map<String, String>> COLUMN_TO_ENTITY_FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 캐시 클리어
     * - NamingStrategy 전역 변경(혹은 컨텍스트 재시작) 시 캐시 오염 방지용
     */
    public static void clearCache() {
        COLUMN_TO_ENTITY_FIELD_CACHE.clear();
    }

    public static boolean isColumnAnnotationPresent(Class<?> clazz) {
        if (clazz == null) return false;
        return ColumnAnalyzer.getAllFields(clazz).stream()
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .anyMatch(f -> f.isAnnotationPresent(Column.class));
    }

    /**
     * @Column이 붙은 필드만 Map에 포함 (key=필드명)
     */
    public static Map<String, Object> toMap(Object obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;

        List<Field> fields = ColumnAnalyzer.getAllFields(obj.getClass());
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            if (!field.isAnnotationPresent(Column.class)) continue;

            try {
                if (!field.canAccess(obj)) field.setAccessible(true);
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

    /**
     * DTO를 엔티티(VO) 기준으로 재키잉한 Map으로 변환한다.
     *
     * - DTO 필드 -> (DB 컬럼명) : @Column(name) 우선, 없으면 NamingStrategy로 계산
     * - 엔티티(VO)에서 (DB 컬럼명 -> 엔티티 필드명) 역매핑 후,
     * - 결과 Map key는 "엔티티 필드명"이 된다.
     */
    public static Map<String, Object> toEntityKeyedMap(Object dto, Class<?> entityClass) {
        if (dto == null || entityClass == null) return Collections.emptyMap();

        Map<String, String> columnToEntityField =
                COLUMN_TO_ENTITY_FIELD_CACHE.computeIfAbsent(entityClass, EntityParser::buildColumnToEntityField);

        Map<String, Object> out = new HashMap<>();

        List<Field> dtoFields = ColumnAnalyzer.getAllFields(dto.getClass());
        for (Field f : dtoFields) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;

            Object value;
            try {
                if (!f.canAccess(dto)) f.setAccessible(true);
                value = f.get(dto);
            } catch (Exception e) {
                log.warn("EntityParser: dto field access failed. field={}", f.getName(), e);
                continue;
            }
            if (value == null) continue;

            String dtoColumn = ColumnAnalyzer.getColumnName(f);
            String entityField = (dtoColumn == null) ? null : columnToEntityField.get(dtoColumn.toLowerCase(Locale.ROOT));

            if (entityField != null) {
                out.put(entityField, value);
            } else {
                out.putIfAbsent(f.getName(), value);
            }
        }

        return out;
    }

    private static Map<String, String> buildColumnToEntityField(Class<?> entityClass) {
        ColumnAnalyzer.TableInfo tableInfo = ColumnAnalyzer.analyzeClass(entityClass);

        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : tableInfo.getFieldColumnMap().entrySet()) {
            String entityField = e.getKey();
            String col = e.getValue();
            if (col != null && !col.isBlank()) {
                m.put(col.toLowerCase(Locale.ROOT), entityField);
            }
        }
        return Collections.unmodifiableMap(m);
    }
}
