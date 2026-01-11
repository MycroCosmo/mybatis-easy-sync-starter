package com.thenoah.dev.mybatis_easy_starter.config;

import com.thenoah.dev.mybatis_easy_starter.tool.generator.AutoSqlBuilder;
import com.thenoah.dev.mybatis_easy_starter.tool.generator.EntityGenerator;
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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class})
public class MybatisEasyAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(MybatisEasyAutoConfiguration.class);

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
        log.info("MyBatis-Easy: Starting Auto Configuration with Externalized SQL Builder...");

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

                // XML 태그 청소
                if (!userContent.isEmpty()) {
                    userContent = userContent.replaceAll("(?s)<mapper.*?>", "")
                            .replaceAll("</mapper>", "")
                            .replaceAll("(?s)<!DOCTYPE.*?>", "")
                            .replaceAll("(?s)<\\?xml.*\\?>", "");
                }

                String autoSql = "";
                try {
                    Class<?> mapperClass = Class.forName(namespace);
                    // [변경 포인트] 외부 분리된 빌더를 호출하도록 수정
                    autoSql = generateAutoSql(mapperClass, userContent);
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

    /**
     * 매퍼가 BaseMapper를 상속받았는지 확인하고, SQL 빌더를 통해 CRUD를 생성합니다.
     */
    private String generateAutoSql(Class<?> mapperClass, String userContent) {
        if (!com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper.class.isAssignableFrom(mapperClass)
                || mapperClass.getGenericInterfaces().length == 0) {
            return "";
        }

        try {
            Type[] interfaces = mapperClass.getGenericInterfaces();
            ParameterizedType type = (ParameterizedType) interfaces[0];
            Class<?> entityClass = (Class<?>) type.getActualTypeArguments()[0];

            // AutoSqlBuilder를 호출
            return AutoSqlBuilder.build(entityClass, userContent);
        } catch (Exception e) {
            log.error("MyBatis-Easy: CRUD generation failed for {}", mapperClass.getName(), e);
            return "";
        }
    }
}