package com.thenoah.dev.mybatis_easy_starter.autosql;

import com.thenoah.dev.mybatis_easy_starter.support.MapperEntityResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MapperEntityResolverTest {

    interface ExtendedMapper extends MemberMapper {
    }

    @Test
    void resolvesEntityFromDirectBaseMapper() {
        assertThat(MapperEntityResolver.resolveEntityType(MemberMapper.class)).isEqualTo(Member.class);
    }

    @Test
    void resolvesEntityThroughInheritedMapperInterface() {
        assertThat(MapperEntityResolver.resolveEntityType(ExtendedMapper.class)).isEqualTo(Member.class);
    }

    @Test
    void returnsNullForNonBaseMapperTypes() {
        assertThat(MapperEntityResolver.resolveEntityType(Runnable.class)).isNull();
        assertThat(MapperEntityResolver.resolveEntityType(null)).isNull();
    }
}
