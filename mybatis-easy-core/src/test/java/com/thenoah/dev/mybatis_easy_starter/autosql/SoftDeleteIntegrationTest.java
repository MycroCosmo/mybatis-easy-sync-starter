package com.thenoah.dev.mybatis_easy_starter.autosql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:autosql;DB_CLOSE_DELAY=-1",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis-easy.autosql.enabled=true"
})
@Transactional
class SoftDeleteIntegrationTest {

    @Autowired
    DocMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private Long insertDoc(String title) {
        Doc d = new Doc();
        d.setTitle(title);
        mapper.insert(d);
        return d.getId();
    }

    @Test
    void deleteByIdMarksRowAndHidesItFromReads() {
        Long id = insertDoc("a");

        assertThat(mapper.deleteById(id)).isEqualTo(1);

        assertThat(mapper.findById(id)).isEmpty();
        assertThat(mapper.findAll()).isEmpty();
        // 물리 삭제가 아니라 deleted_at만 채워진다
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM docs WHERE id = ? AND deleted_at IS NOT NULL", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void deleteByIdOnAlreadyDeletedRowAffectsNothing() {
        Long id = insertDoc("a");
        mapper.deleteById(id);

        assertThat(mapper.deleteById(id)).isZero();
    }

    @Test
    void updateOnDeletedRowAffectsNothing() {
        Long id = insertDoc("a");
        mapper.deleteById(id);

        Doc patch = new Doc();
        patch.setId(id);
        patch.setTitle("b");
        assertThat(mapper.update(patch)).isZero();
    }
}
