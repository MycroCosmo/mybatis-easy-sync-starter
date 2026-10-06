package com.thenoah.dev.mybatis_easy_starter.autosql;

import com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<Member, Long> {
}
