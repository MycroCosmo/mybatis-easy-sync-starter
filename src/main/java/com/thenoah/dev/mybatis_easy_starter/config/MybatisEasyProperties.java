package com.thenoah.dev.mybatis_easy_starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "mybatis-easy")
public class MybatisEasyProperties {

  private final AutoSql autoSql = new AutoSql();
  private final Generator generator = new Generator();
  private final Logging logging = new Logging();

  public AutoSql getAutoSql() { return autoSql; }
  public Generator getGenerator() { return generator; }
  public Logging getLogging() { return logging; }

  public static class AutoSql {
    /**
     * 운영 안전을 위해 기본 OFF 권장
     * (사용자가 명시적으로 켜야 동작)
     */
    private boolean enabled = false;

    private final Update update = new Update();
    private final GeneratedKey generatedKey = new GeneratedKey();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Update getUpdate() { return update; }
    public GeneratedKey getGeneratedKey() { return generatedKey; }

    public static class Update {
      /**
       * true면 업데이트할 필드가 없어도 update 수행(= updated_at만 갱신될 수도 있음)
       * false면 업데이트할 필드가 하나도 없으면 "no-op update(0건)"로 막음
       */
      private boolean allowEmptySet = false;

      public boolean isAllowEmptySet() { return allowEmptySet; }
      public void setAllowEmptySet(boolean allowEmptySet) { this.allowEmptySet = allowEmptySet; }
    }

    public static class GeneratedKey {
      /**
       * AUTO: DB별 추천값 선택(현재는 JDBC로 fallback)
       * JDBC: useGeneratedKeys 기반
       * NONE: 키 회수 안함
       */
      private Strategy strategy = Strategy.AUTO;

      /**
       * 일부 DB/드라이버는 keyColumn 지정이 필요할 수 있음
       * 기본 "id"
       */
      private String keyColumn = "id";

      public Strategy getStrategy() { return strategy; }
      public void setStrategy(Strategy strategy) { this.strategy = strategy; }

      public String getKeyColumn() { return keyColumn; }
      public void setKeyColumn(String keyColumn) { this.keyColumn = keyColumn; }
    }

    public enum Strategy {
      AUTO,
      JDBC,
      NONE
    }
  }

  public static class Generator {
    private boolean enabled = false;
    private boolean useDbFolder = true;
    private boolean enableTablePackage = false;

    private String voRootPackage;

    private Map<String, List<String>> packageMapping = new LinkedHashMap<>();
    private String defaultModule = "misc";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isUseDbFolder() { return useDbFolder; }
    public void setUseDbFolder(boolean useDbFolder) { this.useDbFolder = useDbFolder; }

    public boolean isEnableTablePackage() { return enableTablePackage; }
    public void setEnableTablePackage(boolean enableTablePackage) { this.enableTablePackage = enableTablePackage; }

    public String getVoRootPackage() { return voRootPackage; }
    public void setVoRootPackage(String voRootPackage) { this.voRootPackage = voRootPackage; }

    public Map<String, List<String>> getPackageMapping() { return packageMapping; }
    public void setPackageMapping(Map<String, List<String>> packageMapping) { this.packageMapping = packageMapping; }

    public String getDefaultModule() { return defaultModule; }
    public void setDefaultModule(String defaultModule) { this.defaultModule = defaultModule; }
  }

  public static class Logging {
    private boolean forceStdout = false;
    public boolean isForceStdout() { return forceStdout; }
    public void setForceStdout(boolean forceStdout) { this.forceStdout = forceStdout; }
  }
}
