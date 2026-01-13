package com.thenoah.dev.mybatis_easy_starter.core.interceptor;

import com.thenoah.dev.mybatis_easy_starter.support.EntityParser;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.time.temporal.Temporal;
import java.util.*;

@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class ParameterMappingInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();

        // args[1] = parameter (MyBatis signature)
        if (args.length < 2) {
            return invocation.proceed();
        }

        Object parameter = args[1];
        if (parameter == null) {
            return invocation.proceed();
        }

        // 1) 이미 Map이면 건드리지 않음 (특히 MyBatis ParamMap/StrictMap 포함)
        if (parameter instanceof Map) {
            return invocation.proceed();
        }

        Class<?> clazz = parameter.getClass();

        // 2) 변환하면 안 되는 타입들 (원시/문자열/숫자/날짜/enum/UUID 등)
        if (isPrimitiveLike(clazz)) {
            return invocation.proceed();
        }

        // 3) 컬렉션/배열도 건드리지 않음 (foreach 등 파라미터 깨짐)
        if (parameter instanceof Iterable || clazz.isArray()) {
            return invocation.proceed();
        }

        // 4) 핵심: @Column이 있는 DTO만 Map 변환 (필요할 때만)
        // - @Column이 없는 일반 VO/Entity는 MyBatis 기본 바인딩으로 충분하므로 변환하지 않음
        if (!EntityParser.isColumnAnnotationPresent(clazz)) {
            return invocation.proceed();
        }

        // 5) DTO -> Map (key는 필드명 기준: AutoSqlBuilder와 정합성 맞춤)
        args[1] = EntityParser.toMap(parameter);

        return invocation.proceed();
    }

    /**
     * "변환하면 위험한" 타입들
     * - MyBatis는 이런 타입을 그대로 바인딩하는 게 정상
     */
    private boolean isPrimitiveLike(Class<?> clazz) {
        if (clazz.isPrimitive()) return true;
        if (Number.class.isAssignableFrom(clazz)) return true;
        if (CharSequence.class.isAssignableFrom(clazz)) return true; // String, StringBuilder 등
        if (Boolean.class == clazz || Character.class == clazz) return true;
        if (Date.class.isAssignableFrom(clazz)) return true;
        if (Temporal.class.isAssignableFrom(clazz)) return true; // LocalDateTime 등
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
