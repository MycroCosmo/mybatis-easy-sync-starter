package com.thenoah.dev.mybatis_easy_starter.config;

import com.thenoah.dev.mybatis_easy_starter.core.interceptor.ParameterMappingInterceptor;
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

    /**
     * DTO의 @Column 어노테이션을 분석하여 Map으로 자동 변환해주는 인터셉터 빈 등록
     */
    @Bean
    public ParameterMappingInterceptor parameterMappingInterceptor() {
        return new ParameterMappingInterceptor();
    }

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
        log.info("MyBatis-Easy: Starting Auto Configuration with Automatic DTO Mapping...");

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        // 1. 패키지 스캔 및 Alias 설정
        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        String rootPackage = packages.isEmpty() ? "" : packages.get(0).toLowerCase();

        if (!rootPackage.isEmpty()) {
            factoryBean.setTypeAliasesPackage(rootPackage + ".vo");
        }

        // 2. 매퍼 리소스 가공 및 가상 리소스 생성
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
                String cleanedContent = cleanXmlTag(userContent);

                String autoSql = "";
                try {
                    Class<?> mapperClass = Class.forName(namespace);
                    autoSql = generateAutoSql(mapperClass, cleanedContent);
                } catch (ClassNotFoundException e) {
                    log.debug("MyBatis-Easy: Mapper interface not found for [{}]", namespace);
                }

                StringBuilder virtualXml = new StringBuilder(2048);
                virtualXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                        .append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n")
                        .append("<mapper namespace=\"").append(namespace).append("\">\n")
                        .append(autoSql).append("\n")
                        .append(cleanedContent)
                        .append("\n</mapper>");

                virtualResources.add(new ByteArrayResource(virtualXml.toString().getBytes(StandardCharsets.UTF_8), filename));
            } catch (Exception e) {
                log.error("MyBatis-Easy: Error processing [{}]", filename, e);
            }
        }

        if (!virtualResources.isEmpty()) {
            factoryBean.setMapperLocations(virtualResources.toArray(new Resource[0]));
        }

        // 3. MyBatis 세부 설정 및 인터셉터(플러그인) 등록
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(org.apache.ibatis.logging.stdout.StdOutImpl.class);

        // [핵심 포인트] 리더님이 만드신 인터셉터를 MyBatis 설정에 추가합니다.
        config.addInterceptor(parameterMappingInterceptor());

        factoryBean.setConfiguration(config);

        return factoryBean.getObject();
    }

    /**
     * XML에서 불필요한 태그들을 제거하여 순수 쿼리 내용만 남깁니다.
     */
    private String cleanXmlTag(String content) {
        if (content == null || content.isEmpty()) return "";
        return content.replaceAll("(?s)<mapper.*?>", "")
                      .replaceAll("</mapper>", "")
                      .replaceAll("(?s)<!DOCTYPE.*?>", "")
                      .replaceAll("(?s)<\\?xml.*\\?>", "");
    }

    /**
     * 매퍼 인터페이스를 분석하여 동적 CRUD SQL을 생성합니다.
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

            return AutoSqlBuilder.build(entityClass, userContent);
        } catch (Exception e) {
            log.error("MyBatis-Easy: CRUD generation failed for {}", mapperClass.getName(), e);
            return "";
        }
    }
}