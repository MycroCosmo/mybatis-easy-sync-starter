package com.thenoah.dev.mybatis_easy_starter.autosql;

import com.thenoah.dev.mybatis_easy_starter.config.MybatisEasyProperties;
import com.thenoah.dev.mybatis_easy_starter.config.MybatisEasyProperties.Pagination.FindAll.Policy;
import com.thenoah.dev.mybatis_easy_starter.tool.generator.AutoSqlBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 없이 생성되는 SQL 문자열만 검증하는 방언별 단위 테스트 */
class AutoSqlBuilderTest {

    private static MybatisEasyProperties props() {
        MybatisEasyProperties p = new MybatisEasyProperties();
        p.getPagination().setEnabled(true);
        return p;
    }

    private static String build(Class<?> entity, MybatisEasyProperties p, String db) {
        return AutoSqlBuilder.build(entity, "", p, db);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "postgresql           | OFFSET #{offset} ROWS FETCH NEXT #{__limit} ROWS ONLY",
            "h2                   | OFFSET #{offset} ROWS FETCH NEXT #{__limit} ROWS ONLY",
            "unknown              | OFFSET #{offset} ROWS FETCH NEXT #{__limit} ROWS ONLY",
            "microsoft sql server | OFFSET #{offset} ROWS FETCH NEXT #{__limit} ROWS ONLY",
            "mysql                | LIMIT #{__limit} OFFSET #{offset}",
            "mariadb              | LIMIT #{__limit} OFFSET #{offset}",
            "oracle               | WHERE rn > #{offset}"
    })
    void findPageUsesDialectSyntax(String db, String expected) {
        assertThat(build(Member.class, props(), db)).contains(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "microsoft sql server | SELECT TOP (5)",
            "oracle               | ROWNUM <= 5",
            "mysql                | LIMIT 5",
            "postgresql           | FETCH FIRST 5 ROWS ONLY"
    })
    void findAllCapUsesDialectSyntax(String db, String expected) {
        MybatisEasyProperties p = props();
        p.getPagination().getFindAll().setPolicy(Policy.CAP);
        p.getPagination().getFindAll().setCap(5);

        assertThat(build(Member.class, p, db)).contains(expected);
    }

    @Test
    void findAllDisabledOmitsStatement() {
        MybatisEasyProperties p = props();
        p.getPagination().getFindAll().setPolicy(Policy.DISABLE);

        assertThat(build(Member.class, p, "postgresql")).doesNotContain("id=\"findAll\"");
    }

    @Test
    void findPageAndCountAllOnlyWhenPaginationEnabled() {
        MybatisEasyProperties p = new MybatisEasyProperties();

        String sql = build(Member.class, p, "postgresql");

        assertThat(sql).doesNotContain("id=\"findPage\"").doesNotContain("id=\"countAll\"");
        assertThat(build(Member.class, props(), "postgresql"))
                .contains("id=\"findPage\"").contains("id=\"countAll\"");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "mysql                | `members`",
            "microsoft sql server | [members]",
            "postgresql           | \"members\""
    })
    void quoteIdentifiersFollowsDialect(String db, String expected) {
        MybatisEasyProperties p = props();
        p.getAutoSql().setQuoteIdentifiers(true);

        assertThat(build(Member.class, p, db)).contains("FROM " + expected);
    }

    @Test
    void identifiersAreNotQuotedByDefault() {
        assertThat(build(Member.class, props(), "mysql")).contains("FROM members").doesNotContain("`");
    }

    @Test
    void findByIdAndDeleteByIdBindIdRegardlessOfPkFieldName() {
        String sql = build(Member.class, props(), "postgresql");

        // findById / deleteById는 @Param("id"), update는 엔티티 프로퍼티에 바인딩된다
        assertThat(sql.split("WHERE member_id = #\\{id\\}", -1).length - 1).isEqualTo(2);
        assertThat(sql).contains("WHERE member_id = #{memberId}");
    }

    @Test
    void softDeleteAddsIsNullConditionToReadsAndDeletes() {
        String sql = build(Doc.class, props(), "postgresql");

        assertThat(sql).contains("<update id=\"deleteById\">");
        assertThat(sql.split("deleted_at IS NULL", -1).length - 1).isGreaterThanOrEqualTo(5);
    }

    @Test
    void oracleDoesNotUseJdbcGeneratedKeysByDefault() {
        assertThat(build(Member.class, props(), "oracle")).doesNotContain("useGeneratedKeys");
        assertThat(build(Member.class, props(), "postgresql")).contains("useGeneratedKeys=\"true\"");
    }

    @Test
    void userDefinedStatementsAreNotGenerated() {
        String userXml = "<mapper namespace=\"x\"><select id=\"findById\"></select></mapper>";

        String sql = AutoSqlBuilder.build(Member.class, userXml, props(), "postgresql");

        assertThat(sql).doesNotContain("id=\"findById\"").contains("id=\"insert\"");
    }

    @Test
    void statementsInsideXmlCommentsAreNotTreatedAsUserDefined() {
        String userXml = "<mapper namespace=\"x\"><!-- <select id=\"findAll\"></select> --></mapper>";

        String sql = AutoSqlBuilder.build(Member.class, userXml, props(), "postgresql");

        assertThat(sql).contains("id=\"findAll\"");
    }

    @Test
    void previouslyGeneratedMarkerBlockIsNotTreatedAsUserDefined() {
        String generated = AutoSqlBuilder.build(Member.class, "", props(), "postgresql");
        String userXml = "<mapper namespace=\"x\">\n"
                + "  <!-- MyBatis-Easy: AUTO CRUD BEGIN -->\n" + generated
                + "  <!-- MyBatis-Easy: AUTO CRUD END -->\n</mapper>";

        assertThat(AutoSqlBuilder.build(Member.class, userXml, props(), "postgresql")).isEqualTo(generated);
    }

    @Test
    void userStatementOutsideMarkerBlockStillWins() {
        String userXml = "<mapper namespace=\"x\">\n"
                + "  <!-- MyBatis-Easy: AUTO CRUD BEGIN -->\n"
                + "  <select id=\"findAll\"></select>\n"
                + "  <!-- MyBatis-Easy: AUTO CRUD END -->\n"
                + "  <select id=\"findById\"></select>\n</mapper>";

        String sql = AutoSqlBuilder.build(Member.class, userXml, props(), "postgresql");

        assertThat(sql).contains("id=\"findAll\"").doesNotContain("id=\"findById\"");
    }
}
