package com.thenoah.dev.mybatis_easy_starter.autosql;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;

/** PK 필드명이 "id"가 아닌 엔티티 (memberId / member_id) */
@Table(name = "members")
public class Member {
    @Id
    @Column(name = "member_id")
    private Long memberId;
    private String name;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
