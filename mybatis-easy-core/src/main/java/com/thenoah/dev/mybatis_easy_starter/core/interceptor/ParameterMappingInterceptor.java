package com.thenoah.dev.mybatis_easy_starter.core.interceptor;

import com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper;
import com.thenoah.dev.mybatis_easy_starter.support.EntityParser;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Intercepts({
    // update(ms, param)
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),

    // query(ms, param, rowBounds, resultHandler)
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),

    // query(ms, param, rowBounds, resultHandler, cacheKey, boundSql)  <= 오버로드
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
})
public class ParameterMappingInterceptor implements Interceptor {

  private static final Logger log = LoggerFactory.getLogger(ParameterMappingInterceptor.class);

  // mapperFQCN -> entityClass 캐시
  private final Map<String, Class<?>> entityTypeCache = new ConcurrentHashMap<>();

  // BaseMapper 자동 CRUD 메서드
  private static final Set<String> AUTO_CRUD_METHODS = Set.of(
      "insert", "update", "deleteById", "findById", "findAll", "findPage", "countAll"
  );

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object[] args = invocation.getArgs();
    if (args == null || args.length < 2) return invocation.proceed();

    // 공통: args[0]=MappedStatement, args[1]=parameter
    if (!(args[0] instanceof MappedStatement ms)) return invocation.proceed();
    Object parameter = args[1];
    if (parameter == null) return invocation.proceed();

    // Map/primitive/collection은 그대로
    if (parameter instanceof Map) return invocation.proceed();

    Class<?> pClass = parameter.getClass();
    if (isPrimitiveLike(pClass)) return invocation.proceed();
    if (parameter instanceof Iterable || pClass.isArray()) return invocation.proceed();

    String msId = ms.getId(); // e.g. com.foo.MemberMapper.insert
    int lastDot = msId.lastIndexOf('.');
    if (lastDot <= 0) return invocation.proceed();

    String mapperFqcn = msId.substring(0, lastDot);
    String methodName = msId.substring(lastDot + 1);

    // 자동 CRUD 외에는 개입하지 않음
    if (!AUTO_CRUD_METHODS.contains(methodName)) {
      return invocation.proceed();
    }

    Class<?> entityClass = entityTypeCache.computeIfAbsent(mapperFqcn, this::resolveEntityTypeSafely);
    if (entityClass == null) return invocation.proceed();

    // VO면 그대로
    if (entityClass.isAssignableFrom(pClass)) {
      return invocation.proceed();
    }

    // DTO -> entityKeyed Map 변환
    final Object originalParam = parameter;
    final Map<String, Object> convertedMap = EntityParser.toEntityKeyedMap(originalParam, entityClass);
    args[1] = convertedMap;

    Object result = invocation.proceed();

    // best-effort write-back for generated key (INSERT only)
    tryWriteBackGeneratedKey(ms, originalParam, convertedMap);

    return result;
  }

  private void tryWriteBackGeneratedKey(MappedStatement ms, Object originalParam, Map<String, Object> convertedMap) {
    if (ms == null || originalParam == null || convertedMap == null) return;

    if (ms.getSqlCommandType() != SqlCommandType.INSERT) return;

    if (ms.getKeyGenerator() == null) return;
    // NoKeyGenerator 체크는 구현체 의존이 있어 생략해도 됨. (원하면 유지 가능)

    String keyProp = resolveKeyProperty(ms);
    if (keyProp == null || keyProp.isBlank()) keyProp = "id";

    Object idVal = convertedMap.get(keyProp);
    if (idVal == null) {
      if (log.isDebugEnabled()) {
        log.debug("MyBatis-Easy: write-back skipped (generated key not found in map). msId={} keyProp={}",
            ms.getId(), keyProp);
      }
      return;
    }

    boolean ok = setPropertyOrField(originalParam, keyProp, idVal);
    if (!ok) {
      log.warn("MyBatis-Easy: generated-key write-back failed. targetType={} keyProp={} valueType={}",
          originalParam.getClass().getName(), keyProp, idVal.getClass().getName());
    }
  }

  private String resolveKeyProperty(MappedStatement ms) {
    try {
      String[] keyProps = ms.getKeyProperties();
      if (keyProps != null && keyProps.length > 0 && keyProps[0] != null && !keyProps[0].isBlank()) {
        return keyProps[0];
      }
    } catch (Exception ignored) { }
    return "id";
  }

  private boolean setPropertyOrField(Object target, String prop, Object value) {
    if (target == null || prop == null || prop.isBlank()) return false;

    Class<?> clazz = target.getClass();

    String setterName = "set" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
    Method setter = findSetter(clazz, setterName, value);
    if (setter != null) {
      try {
        if (!setter.canAccess(target)) setter.setAccessible(true);
        Object coerced = coerceValue(value, setter.getParameterTypes()[0]);
        setter.invoke(target, coerced);
        return true;
      } catch (Exception e) {
        log.warn("MyBatis-Easy: write-back setter invoke failed. targetType={} setter={} cause={}",
            clazz.getName(), setterName, e.toString());
        return false;
      }
    }

    Field f = findField(clazz, prop);
    if (f != null) {
      try {
        if (!f.canAccess(target)) f.setAccessible(true);
        Object coerced = coerceValue(value, f.getType());
        f.set(target, coerced);
        return true;
      } catch (Exception e) {
        log.warn("MyBatis-Easy: write-back field set failed. targetType={} field={} cause={}",
            clazz.getName(), prop, e.toString());
        return false;
      }
    }

    return false;
  }

  private Method findSetter(Class<?> clazz, String setterName, Object value) {
    if (clazz == null || setterName == null) return null;

    for (Method m : clazz.getMethods()) {
      if (!m.getName().equals(setterName)) continue;
      if (m.getParameterCount() != 1) continue;
      Class<?> p = m.getParameterTypes()[0];
      if (value == null) return m;
      if (isAssignableAfterCoerce(value.getClass(), p)) return m;
    }
    return null;
  }

  private Field findField(Class<?> clazz, String name) {
    Class<?> cur = clazz;
    while (cur != null && cur != Object.class) {
      try {
        return cur.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cur = cur.getSuperclass();
      }
    }
    return null;
  }

  private boolean isAssignableAfterCoerce(Class<?> from, Class<?> to) {
    if (to.isAssignableFrom(from)) return true;
    if (Number.class.isAssignableFrom(from) && (to.isPrimitive() || Number.class.isAssignableFrom(to))) return true;
    return false;
  }

  private Object coerceValue(Object value, Class<?> targetType) {
    if (value == null) return null;
    if (targetType.isInstance(value)) return value;

    if (targetType == long.class || targetType == Long.class) {
      if (value instanceof Number n) return n.longValue();
      if (value instanceof String s) return Long.parseLong(s);
    }
    if (targetType == int.class || targetType == Integer.class) {
      if (value instanceof Number n) return n.intValue();
      if (value instanceof String s) return Integer.parseInt(s);
    }
    if (targetType == String.class) {
      return String.valueOf(value);
    }
    return value;
  }

  private Class<?> resolveEntityTypeSafely(String mapperFqcn) {
    try {
      Class<?> mapper = Class.forName(mapperFqcn);
      return resolveEntityTypeRecursive(mapper, new HashSet<>());
    } catch (Exception e) {
      return null;
    }
  }

  // BaseMapper<T,ID>의 T를 재귀적으로 찾음
  private Class<?> resolveEntityTypeRecursive(Class<?> type, Set<Class<?>> visited) {
    if (type == null || !visited.add(type)) return null;

    for (Type gi : type.getGenericInterfaces()) {
      Class<?> found = resolveFromType(gi);
      if (found != null) return found;

      if (gi instanceof Class<?> c) {
        Class<?> rec = resolveEntityTypeRecursive(c, visited);
        if (rec != null) return rec;
      } else if (gi instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
        Class<?> rec = resolveEntityTypeRecursive(raw, visited);
        if (rec != null) return rec;
      }
    }

    return resolveEntityTypeRecursive(type.getSuperclass(), visited);
  }

  private Class<?> resolveFromType(Type t) {
    if (!(t instanceof ParameterizedType pt)) return null;

    Type raw = pt.getRawType();
    if (!(raw instanceof Class<?> rawClass)) return null;

    if (!BaseMapper.class.isAssignableFrom(rawClass)) return null;

    Type arg0 = pt.getActualTypeArguments()[0];
    if (arg0 instanceof Class<?> c) return c;

    return null;
  }

  private boolean isPrimitiveLike(Class<?> clazz) {
    if (clazz.isPrimitive()) return true;
    if (Number.class.isAssignableFrom(clazz)) return true;
    if (CharSequence.class.isAssignableFrom(clazz)) return true;
    if (Boolean.class == clazz || Character.class == clazz) return true;
    if (java.util.Date.class.isAssignableFrom(clazz)) return true;
    if (Temporal.class.isAssignableFrom(clazz)) return true;
    if (UUID.class == clazz) return true;
    return Enum.class.isAssignableFrom(clazz);
  }

  @Override
  public Object plugin(Object target) {
    return Plugin.wrap(target, this);
  }

  @Override
  public void setProperties(Properties properties) {
    // no-op
  }
}
