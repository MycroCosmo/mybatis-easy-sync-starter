package com.thenoah.dev.mybatis_easy_starter.config;

import com.thenoah.dev.mybatis_easy_starter.core.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Configuration
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class})
public class MybatisEasyAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(MybatisEasyAutoConfiguration.class);

    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("([a-z])([A-Z])");
    private static final String CAMEL_CASE_REPLACEMENT = "$1_$2";

    @Autowired
    private BeanFactory beanFactory;

    @Value("${mybatis-easy.generator.use-db-folder:true}")
    private boolean useDbFolder;

    /**
     * EntityGenerator 빈 설정.
     * [수정] enabled가 true인 사람(리더)의 환경에서만 동작하도록 원복했습니다.
     * 팀원(false)들은 이 빈 자체가 로드되지 않아 메모리와 구동 속도 면에서 이득을 봅니다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "mybatis-easy.generator", name = "enabled", havingValue = "true")
    public EntityGenerator entityGenerator(DataSource dataSource) {
        EntityGenerator generator = new EntityGenerator();
        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        String basePackage = packages.isEmpty() ? "" : packages.get(0).toLowerCase() + ".vo";

        // 검증 로직 없이 바로 생성/동기화만 수행합니다.
        generator.generate(dataSource, basePackage, useDbFolder);
        return generator;
    }

    @Bean
    @ConditionalOnMissingBean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        log.info("MyBatis-Easy: Starting Auto Configuration...");

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        String rootPackage = packages.isEmpty() ? "" : packages.get(0).toLowerCase();

        if (!rootPackage.isEmpty()) {
            factoryBean.setTypeAliasesPackage(rootPackage + ".vo");
        }

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath:mapper/**/*.xml");
        } catch (Exception e) {
            resources = new Resource[0];
        }

        List<Resource> virtualResources = new ArrayList<>();
        for (Resource res : resources) {
            String filename = res.getFilename();
            if (filename == null) continue;

            try {
                String pureName = filename.substring(0, filename.lastIndexOf(".xml"));
                String namespace = rootPackage + ".mapper." + pureName;

                byte[] bytes = res.getInputStream().readAllBytes();
                String userContent = new String(bytes, StandardCharsets.UTF_8).trim();

                if (!userContent.isEmpty()) {
                    userContent = userContent.replaceAll("(?s)<mapper.*?>", "")
                            .replaceAll("</mapper>", "")
                            .replaceAll("(?s)<!DOCTYPE.*?>", "")
                            .replaceAll("(?s)<\\?xml.*\\?>", "");
                }

                String autoSql = "";
                try {
                    Class<?> mapperClass = Class.forName(namespace);
                    autoSql = generateCrudSql(mapperClass, userContent);
                } catch (ClassNotFoundException e) {
                    log.debug("MyBatis-Easy: Mapper interface not found for [{}]", namespace);
                }

                StringBuilder virtualXml = new StringBuilder(2048);
                virtualXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                        .append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n")
                        .append("<mapper namespace=\"").append(namespace).append("\">\n")
                        .append(autoSql).append("\n")
                        .append(userContent)
                        .append("\n</mapper>");

                virtualResources.add(new ByteArrayResource(virtualXml.toString().getBytes(StandardCharsets.UTF_8), filename));
            } catch (Exception e) {
                log.error("MyBatis-Easy: Error processing [{}]", filename, e);
            }
        }

        if (!virtualResources.isEmpty()) {
            factoryBean.setMapperLocations(virtualResources.toArray(new Resource[0]));
        }

        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(org.apache.ibatis.logging.stdout.StdOutImpl.class);
        factoryBean.setConfiguration(config);

        return factoryBean.getObject();
    }

    private String generateCrudSql(Class<?> mapperClass, String userContent) {
        if (!BaseMapper.class.isAssignableFrom(mapperClass) || mapperClass.getGenericInterfaces().length == 0) {
            return "";
        }
        try {
            Type[] interfaces = mapperClass.getGenericInterfaces();
            ParameterizedType type = (ParameterizedType) interfaces[0];
            Class<?> entityClass = (Class<?>) type.getActualTypeArguments()[0];

            String tableName = entityClass.isAnnotationPresent(Table.class)
                    ? entityClass.getAnnotation(Table.class).name()
                    : entityClass.getSimpleName().toLowerCase();

            String resultTypeName = entityClass.getName();
            Field[] fields = entityClass.getDeclaredFields();

            String pkColumn = "id", pkField = "id";
            List<String> columns = new ArrayList<>();
            List<String> params = new ArrayList<>();

            for (Field field : fields) {
                String fieldName = field.getName();
                String columnName = CAMEL_CASE_PATTERN.matcher(fieldName).replaceAll(CAMEL_CASE_REPLACEMENT).toLowerCase();
                if (field.isAnnotationPresent(Id.class)) {
                    pkColumn = columnName; pkField = fieldName;
                    continue;
                }
                columns.add(columnName);
                params.add("#{" + fieldName + "}");
            }

            StringBuilder sql = new StringBuilder(1024);
            // CRUD 생성 (Insert, findById, findAll, Update, Delete 동일)
            if (!userContent.contains("id=\"insert\"")) {
                sql.append("  <insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"").append(pkField).append("\">\n")
                        .append("    INSERT INTO ").append(tableName)
                        .append(" (").append(String.join(", ", columns)).append(") \n")
                        .append("    VALUES (").append(String.join(", ", params)).append(")\n")
                        .append("  </insert>\n");
            }
            if (!userContent.contains("id=\"findById\"")) {
                sql.append("  <select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
                        .append("    SELECT * FROM ").append(tableName)
                        .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                        .append("  </select>\n");
            }
            if (!userContent.contains("id=\"findAll\"")) {
                sql.append("  <select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n")
                        .append("    SELECT * FROM ").append(tableName).append("\n")
                        .append("  </select>\n");
            }
            if (!userContent.contains("id=\"update\"")) {
                sql.append("  <update id=\"update\">\n")
                        .append("    UPDATE ").append(tableName).append("\n")
                        .append("    <set>\n");
                for (Field field : fields) {
                    if (!field.isAnnotationPresent(Id.class)) {
                        String fieldName = field.getName();
                        String columnName = CAMEL_CASE_PATTERN.matcher(fieldName).replaceAll(CAMEL_CASE_REPLACEMENT).toLowerCase();
                        sql.append("      <if test=\"").append(fieldName).append(" != null\">")
                                .append(columnName).append(" = #{").append(fieldName).append("}, </if>\n");
                    }
                }
                sql.append("    </set>\n")
                        .append("    WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                        .append("  </update>\n");
            }
            if (!userContent.contains("id=\"deleteById\"")) {
                sql.append("  <delete id=\"deleteById\">\n")
                        .append("    DELETE FROM ").append(tableName)
                        .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                        .append("  </delete>\n");
            }
            return sql.toString();
        } catch (Exception e) {
            return "";
        }
    }
}