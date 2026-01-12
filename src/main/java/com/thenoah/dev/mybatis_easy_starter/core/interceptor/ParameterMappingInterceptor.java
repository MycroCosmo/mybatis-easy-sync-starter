package com.thenoah.dev.mybatis_easy_starter.core.interceptor;

import com.thenoah.dev.mybatis_easy_starter.support.EntityParser;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.Map; // 이 부분이 누락되었었습니다.
import java.util.Properties;

@Intercepts({
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class ParameterMappingInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        Object parameter = args[1];

        if (parameter != null) {
            // Map이 아니면서 일반 DTO 객체인 경우 Map으로 자동 변환
            if (!(parameter instanceof Map) && !isPrimitive(parameter.getClass())) {
                args[1] = EntityParser.toMap(parameter);
            }
        }
        return invocation.proceed();
    }

    private boolean isPrimitive(Class<?> clazz) {
        return clazz.isPrimitive() || Number.class.isAssignableFrom(clazz) || clazz == String.class;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {}
}