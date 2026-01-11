package com.thenoah.dev.mybatis_easy_starter.core.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Optional;

/**
 * 모든 매퍼가 상속받을 기본 인터페이스입니다.
 * 사용자가 정의한 VO와 ID 타입을 기반으로 공통 CRUD SQL을 자동 매핑합니다.
 * * @param <T> 엔티티 타입 (예: UserVO)
 * @param <ID> 기본키 타입 (예: Long)
 */
public interface BaseMapper<T, ID> {

    /**
     * 데이터를 삽입합니다.
     * @param entity 삽입할 엔티티 또는 DTO 객체
     * @return 영향받은 행 수
     */
    int insert(T entity);

    /**
     * ID를 기반으로 단건 조회를 수행합니다.
     * AutoSqlBuilder에서 생성한 #{id}와 매칭하기 위해 @Param("id")를 사용합니다.
     * * @param id 조회할 기본키 값
     * @return 조회 결과를 담은 Optional 객체
     */
    Optional<T> findById(@Param("id") ID id);

    /**
     * 모든 데이터를 조회합니다.
     * VO에 @SoftDelete가 설정되어 있다면 삭제되지 않은 데이터만 필터링합니다.
     * * @return 엔티티 리스트
     */
    List<T> findAll();

    /**
     * ID를 기반으로 데이터를 삭제합니다.
     * VO에 @SoftDelete가 설정되어 있다면 내부적으로 UPDATE(논리 삭제)가 수행됩니다.
     * * @param id 삭제할 기본키 값
     * @return 영향받은 행 수
     */
    int deleteById(@Param("id") ID id);

    /**
     * 엔티티 정보를 수정합니다. (ID 기준)
     * 전달된 객체에서 null이 아닌 필드만 선택적으로 업데이트합니다.
     * * @param entity 수정할 데이터를 담은 객체
     * @return 영향받은 행 수
     */
    int update(T entity);
}