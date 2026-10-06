package com.thenoah.dev.mybatis_easy_starter.autosql;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:autosql;DB_CLOSE_DELAY=-1",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis-easy.autosql.enabled=true",
        "mybatis-easy.autosql.generated-key.key-column=member_id"
})
@Transactional
class AutoSqlIntegrationTest {

    @SpringBootApplication
    @MapperScan(basePackageClasses = MemberMapper.class)
    static class TestApp {
    }

    @Autowired
    MemberMapper mapper;

    @Test
    void crudWorksWhenPkFieldIsNotNamedId() {
        Member m = new Member();
        m.setName("alice");
        assertThat(mapper.insert(m)).isEqualTo(1);
        assertThat(m.getMemberId()).isNotNull();

        Long id = m.getMemberId();
        assertThat(mapper.findById(id)).get().extracting(Member::getName).isEqualTo("alice");

        Member patch = new Member();
        patch.setMemberId(id);
        patch.setName("bob");
        assertThat(mapper.update(patch)).isEqualTo(1);
        assertThat(mapper.findById(id)).get().extracting(Member::getName).isEqualTo("bob");

        assertThat(mapper.findAll()).hasSize(1);

        assertThat(mapper.deleteById(id)).isEqualTo(1);
        assertThat(mapper.findById(id)).isEmpty();
    }
}
