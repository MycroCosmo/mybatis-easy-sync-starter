package com.thenoah.dev.mybatis_easy_starter.config;

import com.thenoah.dev.mybatis_easy_starter.core.interceptor.ParameterMappingInterceptor;
import com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper;
import com.thenoah.dev.mybatis_easy_starter.support.naming.DefaultNamingStrategy;
import com.thenoah.dev.mybatis_easy_starter.support.naming.NamingStrategy;
import com.thenoah.dev.mybatis_easy_starter.support.naming.NamingStrategyHolder;
import com.thenoah.dev.mybatis_easy_starter.tool.generator.AutoSqlBuilder;
import com.thenoah.dev.mybatis_easy_starter.tool.generator.EntityGenerator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.mybatis.spring.boot.autoconfigure.SqlSessionFactoryBeanCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AutoConfiguration
@EnableConfigurationProperties(MybatisEasyProperties.class)
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class})
public class MybatisEasyAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MybatisEasyAutoConfiguration.class);

  private final ApplicationContext applicationContext;
  private final BeanFactory beanFactory;
  private final Environment env;

  public MybatisEasyAutoConfiguration(ApplicationContext applicationContext,
                                      BeanFactory beanFactory,
                                      Environment env) {
    this.applicationContext = applicationContext;
    this.beanFactory = beanFactory;
    this.env = env;
  }

  /**
   * mapper XML에서 namespace 추출
   */
  private static final Pattern NAMESPACE_PATTERN =
          Pattern.compile("<mapper[^>]*\\snamespace\\s*=\\s*\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);

  /**
   * CRUD SQL 자동 생성 기능 on/off (기본: true)
   */
  private static final String PROP_AUTOSQL_ENABLED = "mybatis-easy.autosql.enabled";

  /**
   * Entity/XML 생성기 기능 on/off (기본: false 권장)
   */
  private static final String PROP_GENERATOR_ENABLED = "mybatis-easy.generator.enabled";

  @Bean
  public ParameterMappingInterceptor parameterMappingInterceptor() {
    return new ParameterMappingInterceptor();
  }

  /**
   * 기존 MyBatis AutoConfiguration에 Interceptor만 안전하게 추가
   */
  @Bean
  @ConditionalOnClass(ConfigurationCustomizer.class)
  public ConfigurationCustomizer mybatisEasyConfigurationCustomizer(
          ParameterMappingInterceptor interceptor,
          MybatisEasyProperties props
  ) {
    return configuration -> {
      configuration.addInterceptor(interceptor);
      configuration.setMapUnderscoreToCamelCase(true);

      if (props.getLogging().isForceStdout()) {
        configuration.setLogImpl(org.apache.ibatis.logging.stdout.StdOutImpl.class);
      }
    };
  }

  /**
   * NamingStrategy:
   * - 유저가 빈을 제공하면 그걸 사용
   * - 없으면 Default 제공
   */
  @Bean
  @ConditionalOnMissingBean(NamingStrategy.class)
  public NamingStrategy namingStrategy() {
    return new DefaultNamingStrategy();
  }

  /**
   * Holder 초기화:
   * - 실제로 등록된 NamingStrategy(커스텀/기본)를 Holder에 세팅
   */
  @Bean
  public Object namingStrategyHolderInitializer(NamingStrategy namingStrategy) {
    NamingStrategyHolder.set(namingStrategy);
    return new Object();
  }

  /**
   * mapper XML을 원본 유지한 채로 </mapper> 직전에 자동 CRUD SQL만 삽입
   * - namespace는 XML에서 직접 읽음
   * - mapper/postgresql/... 구조 포함
   */
  @Bean
  @ConditionalOnClass(SqlSessionFactoryBeanCustomizer.class)
  @ConditionalOnProperty(name = PROP_AUTOSQL_ENABLED, havingValue = "true", matchIfMissing = true)
  public SqlSessionFactoryBeanCustomizer mybatisEasySqlSessionFactoryBeanCustomizer() {
    return factoryBean -> {

      Resource[] mapperResources = resolveMapperResources();
      if (mapperResources.length == 0) return;

      List<Resource> virtualResources = new ArrayList<>(mapperResources.length);

      for (Resource res : mapperResources) {
        String filename = res.getFilename();
        if (filename == null) {
          virtualResources.add(res);
          continue;
        }

        try (InputStream is = res.getInputStream()) {
          String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          String namespace = extractNamespace(xml);

          if (namespace == null || namespace.isBlank()) {
            virtualResources.add(res);
            continue;
          }

          String autoSql = generateAutoSqlByNamespace(namespace, xml);
          if (autoSql == null || autoSql.isBlank()) {
            virtualResources.add(res);
            continue;
          }

          String merged = injectBeforeClosingMapper(xml, autoSql);
          if (merged == null) {
            virtualResources.add(res);
            continue;
          }

          virtualResources.add(new ByteArrayResource(
                  merged.getBytes(StandardCharsets.UTF_8),
                  "MyBatis-Easy virtual mapper: " + filename + " (" + namespace + ")"
          ));
        } catch (Exception e) {
          log.warn("MyBatis-Easy: Failed to process mapper xml: {}", filename, e);
          virtualResources.add(res);
        }
      }

      factoryBean.setMapperLocations(virtualResources.toArray(new Resource[0]));
      log.info("MyBatis-Easy: mapper xml virtual merge applied. count={}", virtualResources.size());
    };
  }

  /**
   * 개발 편의용 Generator (기본 off 권장)
   */
  @Bean
  @ConditionalOnProperty(name = PROP_GENERATOR_ENABLED, havingValue = "true")
  public EntityGenerator entityGenerator(DataSource dataSource) {

    boolean useDbFolder = env.getProperty("mybatis-easy.generator.use-db-folder", Boolean.class, true);

    EntityGenerator generator = new EntityGenerator();

    List<String> packages = AutoConfigurationPackages.get(beanFactory);
    String basePackage = packages.isEmpty() ? "" : packages.get(0) + ".vo";

    generator.generate(dataSource, basePackage, useDbFolder);
    return generator;
  }

  /**
   * DB별 폴더 구조 유지:
   * - mapper/** 은 mapper/postgresql/... 포함
   * - 필요하면 mybatis-easy/.xml 도 포함 가능
   */
  private Resource[] resolveMapperResources() {
    try {
      PathMatchingResourcePatternResolver resolver =
              new PathMatchingResourcePatternResolver(applicationContext.getClassLoader());

      Resource[] a = resolver.getResources("classpath*:mapper/**/*.xml");

      boolean includeGenerated = env.getProperty("mybatis-easy.mapper.include-generated", Boolean.class, false);
      if (!includeGenerated) {
        return a;
      }

      Resource[] b = resolver.getResources("classpath*:mybatis-easy/mapper/**/*.xml");

      List<Resource> merged = new ArrayList<>(a.length + b.length);
      merged.addAll(Arrays.asList(a));
      merged.addAll(Arrays.asList(b));

      return merged.toArray(new Resource[0]);
    } catch (Exception e) {
      return new Resource[0];
    }
  }

  private String extractNamespace(String xml) {
    Matcher m = NAMESPACE_PATTERN.matcher(xml);
    if (!m.find()) return null;
    return m.group(1);
  }

  private String injectBeforeClosingMapper(String xml, String autoSql) {
    int idx = xml.lastIndexOf("</mapper>");
    if (idx < 0) return null;

    StringBuilder sb = new StringBuilder(xml.length() + autoSql.length() + 16);
    sb.append(xml, 0, idx);

    if (!sb.toString().endsWith("\n")) sb.append("\n");
    sb.append(autoSql);
    if (!autoSql.endsWith("\n")) sb.append("\n");

    sb.append(xml.substring(idx));
    return sb.toString();
  }

  private String generateAutoSqlByNamespace(String namespace, String xmlContent) {
    try {
      Class<?> mapperClass = Class.forName(namespace);

      if (!BaseMapper.class.isAssignableFrom(mapperClass)) {
        return "";
      }

      Class<?> entityClass = resolveEntityType(mapperClass);
      if (entityClass == null) return "";

      return AutoSqlBuilder.build(entityClass, xmlContent);
    } catch (ClassNotFoundException e) {
      log.debug("MyBatis-Easy: namespace is not a class: {}", namespace);
      return "";
    } catch (Exception e) {
      log.warn("MyBatis-Easy: auto sql generation failed for namespace={}", namespace, e);
      return "";
    }
  }

  private Class<?> resolveEntityType(Class<?> mapperClass) {
    for (Type t : mapperClass.getGenericInterfaces()) {
      if (!(t instanceof ParameterizedType pt)) continue;
      Type raw = pt.getRawType();
      if (!(raw instanceof Class<?> rawClass)) continue;

      if (BaseMapper.class.isAssignableFrom(rawClass)) {
        Type arg0 = pt.getActualTypeArguments()[0];
        if (arg0 instanceof Class<?> c) return c;
      }
    }
    return null;
  }
}
