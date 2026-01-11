package com.thenoah.dev.mybatis_easy_starter.core;

import java.util.List;
import java.util.Optional;

/**
 * 모든 매퍼가 상속받을 기본 인터페이스
 * @param <T> 엔티티 타입 (예: User)
 * @param <ID> 기본키 타입 (예: Long)
 */
public interface BaseMapper<T, ID> {

    // 기본 삽입 (id 자동 생성 포함 여부는 DB 설정을 따름)
    int insert(T entity);

    // ID로 단건 조회
    Optional<T> findById(ID id);

    // 전체 조회
    List<T> findAll();

    // ID로 삭제
    int deleteById(ID id);

    // 엔티티 수정 (보통 ID 기준)
    int update(T entity);
}