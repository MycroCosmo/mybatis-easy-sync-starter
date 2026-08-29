package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thenoah.dev.mybatis_easy_starter.config.MybatisEasyProperties;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AutoSqlBuilderTest {

  @Test
  void generatesMysqlPaginationWithBoundedArgumentsAndSoftDeleteFilter() {
    MybatisEasyProperties props = pagination(MybatisEasyProperties.Pagination.Dialect.MYSQL);

    String sql = AutoSqlBuilder.build(Account.class, "", props, "MySQL");

    assertThat(sql)
        .contains("<insert id=\"insert\"")
        .contains("<select id=\"findById\"")
        .contains("WHERE deleted_at IS NULL")
        .contains("name=\"__offset\" value=\"offset &lt; 0 ? 0 : offset\"")
        .contains("name=\"__limit\" value=\"limit &lt; 1 ? 1 : (limit > 50 ? 50 : limit)\"")
        .contains("LIMIT #{__limit} OFFSET #{__offset}");
  }

  @Test
  void generatesConfiguredPostgresPaginationInsteadOfDatabaseGuess() {
    MybatisEasyProperties props = pagination(MybatisEasyProperties.Pagination.Dialect.POSTGRES);

    String sql = AutoSqlBuilder.build(Account.class, "", props, "MySQL");

    assertThat(sql).contains("OFFSET #{__offset} ROWS FETCH NEXT #{__limit} ROWS ONLY");
    assertThat(sql).doesNotContain("LIMIT #{__limit}");
  }

  @Test
  void preservesUserDefinedStatements() {
    String userXml = "<select id=\"findById\" resultType=\"map\">SELECT 1</select>";

    String sql = AutoSqlBuilder.build(Account.class, userXml, new MybatisEasyProperties(), "H2");

    assertThat(sql).doesNotContain("<select id=\"findById\"");
    assertThat(sql).contains("<insert id=\"insert\"");
  }

  @Test
  void failsFastWhenEntityMetadataCannotBeAnalyzed() {
    assertThatThrownBy(() -> AutoSqlBuilder.build(null, "", new MybatisEasyProperties(), "H2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("<null>")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  private MybatisEasyProperties pagination(MybatisEasyProperties.Pagination.Dialect dialect) {
    MybatisEasyProperties props = new MybatisEasyProperties();
    props.getPagination().setEnabled(true);
    props.getPagination().setDialect(dialect);
    props.getPagination().setMaxPageSize(50);
    return props;
  }

  @Table(name = "accounts")
  static class Account {
    @Id
    private Long id;
    private String displayName;
    @SoftDelete
    private Instant deletedAt;
  }
}
