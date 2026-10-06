package com.thenoah.dev.mybatis_easy_starter.autosql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:autosql;DB_CLOSE_DELAY=-1",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis-easy.autosql.enabled=true",
        "mybatis-easy.autosql.generated-key.key-column=member_id"
})
@Transactional
class DtoMappingIntegrationTest {

    @Autowired
    MemberMapper mapper;

    @Test
    void insertWithDtoMapsColumnsAndWritesBackGeneratedKey() {
        MemberDto dto = new MemberDto();
        dto.setDisplayName("alice");

        assertThat(mapper.insert(dto)).isEqualTo(1);

        assertThat(dto.getMemberId()).isNotNull();
        assertThat(mapper.findById(dto.getMemberId()))
                .get().extracting(Member::getName).isEqualTo("alice");
    }

    @Test
    void updateWithDtoChangesOnlyProvidedFields() {
        MemberDto created = new MemberDto();
        created.setDisplayName("alice");
        mapper.insert(created);

        MemberDto patch = new MemberDto();
        patch.setMemberId(created.getMemberId());
        patch.setDisplayName("bob");

        assertThat(mapper.update(patch)).isEqualTo(1);
        assertThat(mapper.findById(created.getMemberId()))
                .get().extracting(Member::getName).isEqualTo("bob");
    }

    @Test
    void updateWithDtoWithoutUpdatableFieldsIsNoOp() {
        Member m = new Member();
        m.setName("alice");
        mapper.insert(m);

        MemberDto patch = new MemberDto();
        patch.setMemberId(m.getMemberId());

        assertThat(mapper.update(patch)).isZero();
        assertThat(mapper.findById(m.getMemberId()))
                .get().extracting(Member::getName).isEqualTo("alice");
    }
}
