package com.thenoah.dev.mybatis_easy_starter.autosql;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.SoftDelete;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;

import java.time.LocalDateTime;

@Table(name = "docs")
public class Doc {
    @Id
    private Long id;
    private String title;
    @SoftDelete
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
