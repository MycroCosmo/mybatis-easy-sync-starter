package com.thenoah.dev.mybatis_easy_starter.autosql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** mybatis.mapper-locations를 기본 경로(mapper/**)가 아닌 곳으로 지정해도 AutoSQL이 병합되어야 한다. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:autosql;DB_CLOSE_DELAY=-1",
        "mybatis.mapper-locations=classpath*:custom-mappers/**/*.xml",
        "mybatis-easy.autosql.enabled=true"
})
@Transactional
class CustomMapperLocationsIntegrationTest {

    @Autowired
    ItemMapper mapper;

    @Test
    void autoSqlIsMergedForUserConfiguredMapperLocations() {
        Item item = new Item();
        item.setLabel("x");

        assertThat(mapper.insert(item)).isEqualTo(1);
        assertThat(mapper.findById(item.getId())).get().extracting(Item::getLabel).isEqualTo("x");
    }
}
