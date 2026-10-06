package com.thenoah.dev.mybatis_easy_starter.autosql;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;

/** 엔티티가 아닌 DTO. displayName은 @Column으로 엔티티의 name 컬럼에 매핑된다. */
public class MemberDto {
    private Long memberId;
    @Column(name = "name")
    private String displayName;
    private String ignoredByEntity;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getIgnoredByEntity() { return ignoredByEntity; }
    public void setIgnoredByEntity(String ignoredByEntity) { this.ignoredByEntity = ignoredByEntity; }
}
