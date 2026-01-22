package com.thenoah.dev.mybatis_easy_starter.core.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Optional;

/**
 * 모든 매퍼가 상속받을 기본 인터페이스입니다.
 * MybatisEasyAutoConfiguration에서 생성한 가상 XML의 SQL과 자동으로 매핑됩니다.
 *
 * @param <T> 엔티티 타입 (예: Member)
 * @param <ID> 기본키 타입 (예: Long)
 */
public interface BaseMapper<T, ID> {

    /**
     * 데이터를 삽입합니다. (가상 XML의 id="insert"와 매핑)
     * 파라미터 타입을 Object로 설정하여 VO뿐만 아니라 DTO도 수용 가능합니다.
     */
    int insert(Object entity);

    /**
     * ID를 기반으로 단건 조회를 수행합니다. (가상 XML의 id="findById"와 매핑)
     */
    Optional<T> findById(@Param("id") ID id);

    /**
     * 모든 데이터를 조회합니다. (가상 XML의 id="findAll"와 매핑)
     * - 권장하지 않는 메서드 (대신 findPage 사용 권장)
     */
    List<T> findAll();

    /**
     * Slice 스타일 페이징 (COUNT 없이 데이터만 조회)
     * 가상 XML의 id="findPage"와 매핑
     *
     * @param offset 시작 위치 (0부터)
     * @param limit  페이지 크기
     */
    List<T> findPage(@Param("offset") long offset,
                     @Param("limit") int limit);

    /**
     * 전체 건수 조회 (Page 스타일이 필요할 때만 사용)
     * 가상 XML의 id="countAll"와 매핑
     */
    long countAll();

    /**
     * ID를 기반으로 데이터를 삭제합니다. (가상 XML의 id="deleteById"와 매핑)
     * SoftDelete 설정 시 내부적으로 UPDATE 쿼리가 실행됩니다.
     */
    int deleteById(@Param("id") ID id);

    /**
     * 데이터를 수정합니다. (가상 XML의 id="update"와 매핑)
     * 파라미터 타입을 Object로 설정하여 DTO 기반 수정이 가능합니다.
     */
    int update(Object entity);
}
