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

    @Bean
    @ConditionalOnProperty(prefix = "mybatis-easy.generator", name = "enabled", havingValue = "true")
    public EntityGenerator entityGenerator(DataSource dataSource) {
        EntityGenerator generator = new EntityGenerator();
        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        String basePackage = packages.isEmpty() ? "" : packages.get(0).toLowerCase() + ".vo";
        generator.generate(dataSource, basePackage, useDbFolder);
        return generator;
    }
    
    @Bean
    @ConditionalOnMissingBean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        log.info("MyBatis-Easy: Starting Auto Configuration with CRUD generation...");
        
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        // 1. Alias 설정을 위한 패키지 경로 추출
        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        String rootPackage = packages.isEmpty() ? "" : packages.get(0).toLowerCase();
        
        if (!rootPackage.isEmpty()) {
            // @Alias("별칭") 어노테이션을 인식하도록 vo 패키지 등록
            factoryBean.setTypeAliasesPackage(rootPackage + ".vo");
            log.info("MyBatis-Easy: Type aliases package set to [{}.vo]", rootPackage);
        }

        // 2. 가상 XML 리소스 수집 시작
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

                // XML 내부 태그 정제 (mapper, doctype 등 제거)
                if (!userContent.isEmpty()) {
                    userContent = userContent.replaceAll("(?s)<mapper.*?>", "")
                                             .replaceAll("</mapper>", "")
                                             .replaceAll("(?s)<!DOCTYPE.*?>", "")
                                             .replaceAll("(?s)<\\?xml.*\\?>", "");
                }

                // CRUD SQL 자동 생성
                String autoSql = "";
                try {
                    Class<?> mapperClass = Class.forName(namespace);
                    autoSql = generateCrudSql(mapperClass, userContent);
                } catch (ClassNotFoundException e) {
                    log.debug("MyBatis-Easy: Mapper interface not found for namespace [{}]", namespace);
                }

                // 최종 가상 XML 조립
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

        // 💡 3. 수집된 리소스가 있을 때만 MyBatis에 등록 (경고 방지)
        if (!virtualResources.isEmpty()) {
            factoryBean.setMapperLocations(virtualResources.toArray(new Resource[0]));
            log.info("MyBatis-Easy: Successfully registered {} virtual mapper resources.", virtualResources.size());
        } else {
            log.warn("MyBatis-Easy: No mapper resources found. Check your 'src/main/resources/mapper' folder.");
        }
        
        // 4. MyBatis 기본 설정 (CamelCase 등)
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
            // Insert
            if (!userContent.contains("id=\"insert\"")) {
                sql.append("<insert id=\"insert\" useGeneratedKeys=\"true\" keyProperty=\"").append(pkField).append("\">\n")
                   .append("  INSERT INTO ").append(tableName)
                   .append(" (").append(String.join(", ", columns)).append(") \n")
                   .append("  VALUES (").append(String.join(", ", params)).append(")\n")
                   .append("</insert>\n");
            }
            // findById
            if (!userContent.contains("id=\"findById\"")) {
                sql.append("<select id=\"findById\" resultType=\"").append(resultTypeName).append("\">\n")
                   .append("  SELECT * FROM ").append(tableName)
                   .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                   .append("</select>\n");
            }
            // findAll
            if (!userContent.contains("id=\"findAll\"")) {
                sql.append("<select id=\"findAll\" resultType=\"").append(resultTypeName).append("\">\n")
                   .append("  SELECT * FROM ").append(tableName).append("\n")
                   .append("</select>\n");
            }
            // Update
            if (!userContent.contains("id=\"update\"")) {
                sql.append("<update id=\"update\">\n")
                   .append("  UPDATE ").append(tableName).append("\n")
                   .append("  <set>\n");
                for (Field field : fields) {
                    if (!field.isAnnotationPresent(Id.class)) {
                        String fieldName = field.getName();
                        String columnName = CAMEL_CASE_PATTERN.matcher(fieldName).replaceAll(CAMEL_CASE_REPLACEMENT).toLowerCase();
                        sql.append("    <if test=\"").append(fieldName).append(" != null\">")
                           .append(columnName).append(" = #{").append(fieldName).append("}, </if>\n");
                    }
                }
                sql.append("  </set>\n")
                   .append("  WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                   .append("</update>\n");
            }
            // Delete
            if (!userContent.contains("id=\"deleteById\"")) {
                sql.append("<delete id=\"deleteById\">\n")
                   .append("  DELETE FROM ").append(tableName)
                   .append(" WHERE ").append(pkColumn).append(" = #{").append(pkField).append("}\n")
                   .append("</delete>\n");
            }
            return sql.toString();
        } catch (Exception e) {
            return "";
        }
    }
}