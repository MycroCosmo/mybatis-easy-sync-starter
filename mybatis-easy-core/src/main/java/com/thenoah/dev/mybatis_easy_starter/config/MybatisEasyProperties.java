package com.thenoah.dev.mybatis_easy_starter.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mybatis-easy")
public class MybatisEasyProperties {

  private final AutoSql autoSql = new AutoSql();
  private final Generator generator = new Generator();
  private final Logging logging = new Logging();
  private final Pagination pagination = new Pagination();

  public AutoSql getAutoSql() { return autoSql; }
  public Generator getGenerator() { return generator; }
  public Logging getLogging() { return logging; }
  public Pagination getPagination() { return pagination; }

  // ------------------------------------------------------------
  // AutoSql
  // ------------------------------------------------------------
  public static class AutoSql {
    /**
     * 운영 안전을 위해 기본 OFF 권장
     * (사용자가 명시적으로 켜야 동작)
     */
    private boolean enabled = false;

    /**
     * XML에 마커 블록이 이미 있을 때의 갱신 정책
     * NONE: 마커 있으면 기존처럼 스킵
     * UPDATE_MARKER_BLOCK: 마커 블록 내부를 항상 최신 엔티티 구조로 교체
     */
    private RefreshMode refreshMode = RefreshMode.NONE;

    /**
     * 예약어/특수문자 대응을 위한 identifier quoting
     * true면 dialect에 따라 table/column에 quote 적용
     */
    private boolean quoteIdentifiers = false;

    private final Update update = new Update();
    private final GeneratedKey generatedKey = new GeneratedKey();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public RefreshMode getRefreshMode() { return refreshMode; }
    public void setRefreshMode(RefreshMode refreshMode) { this.refreshMode = refreshMode; }

    public boolean isQuoteIdentifiers() { return quoteIdentifiers; }
    public void setQuoteIdentifiers(boolean quoteIdentifiers) { this.quoteIdentifiers = quoteIdentifiers; }

    public Update getUpdate() { return update; }
    public GeneratedKey getGeneratedKey() { return generatedKey; }

    public enum RefreshMode { NONE, UPDATE_MARKER_BLOCK }

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

    public enum Strategy { AUTO, JDBC, NONE }
  }

  // ------------------------------------------------------------
  // Generator
  // ------------------------------------------------------------
  public static class Generator {
    private boolean enabled = false;

    /**
     * 2중 스위치: enabled=true + allowWrite=true에서만 파일 쓰기 허용
     * 기본 false
     */
    private boolean allowWrite = false;

    private boolean useDbFolder = true;
    private boolean enableTablePackage = false;

    private String voRootPackage;

    private Map<String, List<String>> packageMapping = new LinkedHashMap<>();
    private String defaultModule = "misc";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAllowWrite() { return allowWrite; }
    public void setAllowWrite(boolean allowWrite) { this.allowWrite = allowWrite; }

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

  // ------------------------------------------------------------
  // Logging
  // ------------------------------------------------------------
  public static class Logging {
    private boolean forceStdout = false;

    public boolean isForceStdout() { return forceStdout; }
    public void setForceStdout(boolean forceStdout) { this.forceStdout = forceStdout; }
  }

  // ------------------------------------------------------------
  // Pagination
  // ------------------------------------------------------------
  public static class Pagination {

    /**
     * enabled=true 인 경우에만:
     * - findPage SQL 생성
     * - countAll SQL 생성
     */
    private boolean enabled = false;

    /** AUTO / POSTGRES / MYSQL / MARIADB / ORACLE / SQLSERVER / H2 / SQLITE */
    private Dialect dialect = Dialect.AUTO;

    /** 과도한 size 방지 (findPage에서 clamp) */
    private int maxPageSize = 200;

    /**
     * DB별 now() 함수 override (필요 시)
     * 예: SQLServer에서 CURRENT_TIMESTAMP 대신 SYSUTCDATETIME() 강제
     */
    private String nowFunction;

    /** findAll 정책 (열어두되 운영사고 줄이기) */
    private final FindAll findAll = new FindAll();

    /** findPage 기본 정렬 정책 */
    private final DefaultOrder defaultOrder = new DefaultOrder();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Dialect getDialect() { return dialect; }
    public void setDialect(Dialect dialect) { this.dialect = dialect; }

    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }

    public String getNowFunction() { return nowFunction; }
    public void setNowFunction(String nowFunction) { this.nowFunction = nowFunction; }

    public FindAll getFindAll() { return findAll; }
    public DefaultOrder getDefaultOrder() { return defaultOrder; }

    public enum Dialect { AUTO, POSTGRES, MYSQL, MARIADB, ORACLE, SQLSERVER, H2, SQLITE }

    public static class FindAll {
      private Policy policy = Policy.NONE; // NONE / CAP / DISABLE
      private int cap = 1000;             // CAP일 때 최대 조회 수

      public Policy getPolicy() { return policy; }
      public void setPolicy(Policy policy) { this.policy = policy; }

      public int getCap() { return cap; }
      public void setCap(int cap) { this.cap = cap; }

      public enum Policy { NONE, CAP, DISABLE }
    }

    public static class DefaultOrder {
      private Mode mode = Mode.AUTO;                 // AUTO / CREATED_AT / UPDATED_AT / PK / NONE
      private Direction direction = Direction.DESC;  // ASC / DESC

      public Mode getMode() { return mode; }
      public void setMode(Mode mode) { this.mode = mode; }

      public Direction getDirection() { return direction; }
      public void setDirection(Direction direction) { this.direction = direction; }

      public enum Mode { AUTO, CREATED_AT, UPDATED_AT, PK, NONE }
      public enum Direction { ASC, DESC }
    }
  }
}
