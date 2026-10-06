package com.thenoah.dev.mybatis_easy_starter.support;

import com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

/**
 * Mapper 인터페이스에서 BaseMapper&lt;T, ID&gt;의 T(엔티티 타입)를
 * 상속/중첩 인터페이스까지 재귀로 탐색해 찾는다.
 */
public final class MapperEntityResolver {

  private MapperEntityResolver() {}

  /** 찾지 못하면 null */
  public static Class<?> resolveEntityType(Class<?> mapperClass) {
    return resolveRecursive(mapperClass, new HashSet<>());
  }

  private static Class<?> resolveRecursive(Class<?> type, Set<Class<?>> visited) {
    if (type == null || !visited.add(type)) return null;

    for (Type gi : type.getGenericInterfaces()) {
      Class<?> found = resolveFromType(gi);
      if (found != null) return found;

      if (gi instanceof Class<?> c) {
        Class<?> rec = resolveRecursive(c, visited);
        if (rec != null) return rec;
      } else if (gi instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
        Class<?> rec = resolveRecursive(raw, visited);
        if (rec != null) return rec;
      }
    }

    return resolveRecursive(type.getSuperclass(), visited);
  }

  private static Class<?> resolveFromType(Type t) {
    if (!(t instanceof ParameterizedType pt)) return null;
    if (!(pt.getRawType() instanceof Class<?> rawClass)) return null;
    if (!BaseMapper.class.isAssignableFrom(rawClass)) return null;

    Type arg0 = pt.getActualTypeArguments()[0];
    return (arg0 instanceof Class<?> c) ? c : null;
  }
}
