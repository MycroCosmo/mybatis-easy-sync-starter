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
import java.sql.Connection;
import java.util.*;
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

  // namespace="..." 또는 namespace='...' 지원
  private static final Pattern NAMESPACE_PATTERN =
      Pattern.compile("<mapper[^>]*\\snamespace\\s*=\\s*([\"'])([^\"']+)\\1[^>]*>", Pattern.CASE_INSENSITIVE);

  // </mapper> 대소문자/공백 변형 대응
  private static final Pattern CLOSING_MAPPER_PATTERN =
      Pattern.compile("</mapper\\s*>", Pattern.CASE_INSENSITIVE);

  private static final String PROP_AUTOSQL_ENABLED = "mybatis-easy.autosql.enabled";
  private static final String PROP_GENERATOR_ENABLED = "mybatis-easy.generator.enabled";

  private static final String MYBATIS_EASY_MARKER =
      "  <!-- MyBatis-Easy: AUTO CRUD BEGIN -->\n";
  private static final String MYBATIS_EASY_MARKER_END =
      "  <!-- MyBatis-Easy: AUTO CRUD END -->\n";

  @Bean
  public ParameterMappingInterceptor parameterMappingInterceptor() {
    return new ParameterMappingInterceptor();
  }

  @Bean
  @ConditionalOnClass(ConfigurationCustomizer.class)
  public ConfigurationCustomizer mybatisEasyConfigurationCustomizer(
      ParameterMappingInterceptor interceptor,
      MybatisEasyProperties props
  ) {
    return configuration -> {
      configuration.addInterceptor(interceptor);

      // ✅ 강제 설정 제거 (사용자 MyBatis 정책 침범 방지)
      // configuration.setMapUnderscoreToCamelCase(true);

      if (props.getLogging().isForceStdout()) {
        configuration.setLogImpl(org.apache.ibatis.logging.stdout.StdOutImpl.class);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(NamingStrategy.class)
  public NamingStrategy namingStrategy() {
    return new DefaultNamingStrategy();
  }

  @Bean
  public Object namingStrategyHolderInitializer(NamingStrategy namingStrategy) {
    NamingStrategyHolder.set(namingStrategy);
    return new Object();
  }

  /**
   * AutoSql: 기본 OFF
   * - application.yml에서 mybatis-easy.autosql.enabled=true 일 때만 켜짐
   * - + props.getAutoSql().enabled도 true여야 동작
   */
  @Bean
  @ConditionalOnClass(SqlSessionFactoryBeanCustomizer.class)
  @ConditionalOnProperty(name = PROP_AUTOSQL_ENABLED, havingValue = "true", matchIfMissing = false)
  public SqlSessionFactoryBeanCustomizer mybatisEasySqlSessionFactoryBeanCustomizer(MybatisEasyProperties props) {
    return factoryBean -> {

      if (!props.getAutoSql().isEnabled()) {
        log.info("MyBatis-Easy: autosql.enabled=false (props). skip xml merge.");
        return;
      }

      Resource[] mapperResources = resolveMapperResources();
      if (mapperResources.length == 0) return;

      // ✅ 매퍼마다 커넥션 오픈 방지: 1회 조회/캐시
      final String dbProductName = resolveDbProductName();

      List<Resource> virtualResources = new ArrayList<>(mapperResources.length);

      for (Resource res : mapperResources) {
        String filename = res.getFilename();
        if (filename == null) {
          virtualResources.add(res);
          continue;
        }

        try (InputStream is = res.getInputStream()) {
          String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);

          // 재삽입 방지
          if (xml.contains(MYBATIS_EASY_MARKER) || xml.contains(MYBATIS_EASY_MARKER_END)) {
            virtualResources.add(res);
            continue;
          }

          String namespace = extractNamespace(xml);
          if (namespace == null || namespace.isBlank()) {
            virtualResources.add(res);
            continue;
          }

          String autoSql = generateAutoSqlByNamespace(namespace, xml, props, dbProductName);

          if (autoSql == null || autoSql.isBlank()) {
            virtualResources.add(res);
            continue;
          }

          String merged = injectBeforeClosingMapper(xml, MYBATIS_EASY_MARKER + autoSql + MYBATIS_EASY_MARKER_END);
          if (merged == null) {
            log.warn("MyBatis-Easy: could not inject auto sql (closing </mapper> not found). file={} ns={}",
                res.getDescription(), namespace);
            virtualResources.add(res);
            continue;
          }

          virtualResources.add(new ByteArrayResource(
              merged.getBytes(StandardCharsets.UTF_8),
              "MyBatis-Easy virtual mapper: " + filename + " (" + namespace + ")"
          ));
        } catch (Exception e) {
          log.warn("MyBatis-Easy: Failed to process mapper xml: {}", res.getDescription(), e);
          virtualResources.add(res);
        }
      }

      factoryBean.setMapperLocations(virtualResources.toArray(new Resource[0]));
      log.info("MyBatis-Easy: mapper xml virtual merge applied. count={}", virtualResources.size());
    };
  }

  @Bean
  @ConditionalOnProperty(name = PROP_GENERATOR_ENABLED, havingValue = "true")
  public EntityGenerator entityGenerator(DataSource dataSource, MybatisEasyProperties props) {
    MybatisEasyProperties.Generator g = props.getGenerator();

    boolean useDbFolder = g.isUseDbFolder();
    boolean enableTablePackage = g.isEnableTablePackage();

    List<String> packages = AutoConfigurationPackages.get(beanFactory);
    String appBasePackage = packages.isEmpty() ? "" : packages.get(0);
    String basePackage = appBasePackage.isBlank() ? "" : appBasePackage + ".vo";

    EntityGenerator generator = new EntityGenerator();
    generator.generate(
        dataSource,
        basePackage,
        useDbFolder,
        enableTablePackage,
        g.getVoRootPackage(),
        g.getPackageMapping(),
        g.getDefaultModule()
    );

    return generator;
  }

  private Resource[] resolveMapperResources() {
    try {
      PathMatchingResourcePatternResolver resolver =
          new PathMatchingResourcePatternResolver(applicationContext.getClassLoader());

      Resource[] a = resolver.getResources("classpath*:mapper/**/*.xml");

      boolean includeGenerated = env.getProperty("mybatis-easy.mapper.include-generated", Boolean.class, false);
      if (!includeGenerated) {
        return dedupAndSort(a);
      }

      Resource[] b = resolver.getResources("classpath*:mybatis-easy/mapper/**/*.xml");

      List<Resource> merged = new ArrayList<>(a.length + b.length);
      merged.addAll(Arrays.asList(a));
      merged.addAll(Arrays.asList(b));

      return dedupAndSort(merged.toArray(new Resource[0]));
    } catch (Exception e) {
      log.warn("MyBatis-Easy: resolveMapperResources failed", e);
      return new Resource[0];
    }
  }

  /**
   * ✅ 중복 제거 + 안정적 순서(정렬)
   * - Resource.getDescription() 기준으로 중복 제거
   * - description 기준 정렬(빌드/환경에 따른 비결정성 완화)
   */
  private Resource[] dedupAndSort(Resource[] resources) {
    if (resources == null || resources.length == 0) return new Resource[0];

    Map<String, Resource> uniq = new LinkedHashMap<>();
    for (Resource r : resources) {
      if (r == null) continue;
      String key = r.getDescription();
      uniq.putIfAbsent(key, r);
    }

    List<Resource> list = new ArrayList<>(uniq.values());
    list.sort(Comparator.comparing(Resource::getDescription, Comparator.nullsLast(String::compareTo)));

    return list.toArray(new Resource[0]);
  }

  private String extractNamespace(String xml) {
    Matcher m = NAMESPACE_PATTERN.matcher(xml);
    if (!m.find()) return null;
    return m.group(2);
  }

  /**
   * ✅ </mapper> 정규식 기반으로 마지막 closing 태그 직전에 삽입
   */
  private String injectBeforeClosingMapper(String xml, String payload) {
    if (xml == null) return null;

    Matcher m = CLOSING_MAPPER_PATTERN.matcher(xml);
    int lastStart = -1;
    while (m.find()) lastStart = m.start();
    if (lastStart < 0) return null;

    StringBuilder sb = new StringBuilder(xml.length() + payload.length() + 32);
    sb.append(xml, 0, lastStart);

    if (!sb.toString().endsWith("\n")) sb.append("\n");
    sb.append(payload);
    if (!payload.endsWith("\n")) sb.append("\n");

    sb.append(xml.substring(lastStart));
    return sb.toString();
  }

  private String generateAutoSqlByNamespace(String namespace,
                                            String xmlContent,
                                            MybatisEasyProperties props,
                                            String dbProductName) {
    try {
      ClassLoader cl = applicationContext.getClassLoader();
      Class<?> mapperClass = Class.forName(namespace, false, cl);

      if (!BaseMapper.class.isAssignableFrom(mapperClass)) {
        return "";
      }

      Class<?> entityClass = resolveEntityType(mapperClass);
      if (entityClass == null) return "";

      return AutoSqlBuilder.build(entityClass, xmlContent, props.getAutoSql(), dbProductName);
    } catch (ClassNotFoundException e) {
      log.debug("MyBatis-Easy: namespace is not a class: {}", namespace);
      return "";
    } catch (Exception e) {
      log.warn("MyBatis-Easy: auto sql generation failed for namespace={}", namespace, e);
      return "";
    }
  }

  private String resolveDbProductName() {
    try {
      DataSource ds = applicationContext.getBean(DataSource.class);
      try (Connection c = ds.getConnection()) {
        String name = c.getMetaData().getDatabaseProductName();
        return (name == null) ? "unknown" : name.toLowerCase(Locale.ROOT);
      }
    } catch (Exception e) {
      return "unknown";
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
