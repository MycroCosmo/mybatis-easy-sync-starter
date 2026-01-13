package com.thenoah.dev.mybatis_easy_starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mybatis-easy")
public class MybatisEasyProperties {

  private final AutoSql autoSql = new AutoSql();
  private final Generator generator = new Generator();
  private final Logging logging = new Logging();

  public AutoSql getAutoSql() { return autoSql; }
  public Generator getGenerator() { return generator; }
  public Logging getLogging() { return logging; }

  public static class AutoSql {
    /** 자동 CRUD SQL 삽입 기능 */
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
  }

  public static class Generator {
    /** 개발환경에서만 권장 */
    private boolean enabled = false;
    private boolean useDbFolder = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isUseDbFolder() { return useDbFolder; }
    public void setUseDbFolder(boolean useDbFolder) { this.useDbFolder = useDbFolder; }
  }

  public static class Logging {
    /** 사용자가 MyBatis 로그 구현을 설정했다면 그 설정을 존중하는 게 기본 */
    private boolean forceStdout = false;
    public boolean isForceStdout() { return forceStdout; }
    public void setForceStdout(boolean forceStdout) { this.forceStdout = forceStdout; }
  }
}
