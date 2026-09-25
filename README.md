# MyBatis Easy Sync Starter

MyBatis를 사용하면서 반복되는 CRUD 작성과 Mapper/XML 불일치 문제를 줄이기 위해 만든 Java 17 기반 라이브러리입니다.

이 프로젝트의 목표는 MyBatis를 ORM처럼 대체하는 것이 아닙니다.  
**반복 가능한 영역은 자동화하되, 커스텀 SQL에 대한 개발자의 제어권은 유지하는 것**에 초점을 맞췄습니다.

## 문제 정의

MyBatis 프로젝트를 진행하면서 다음 문제가 반복됐습니다.

- 테이블마다 비슷한 기본 CRUD SQL을 반복 작성
- Java 필드와 DB 컬럼 구조가 변경되면서 코드와 스키마가 어긋남
- Mapper 인터페이스와 XML statement id가 불일치
- 자동 생성 도구가 기존 커스텀 SQL까지 덮어쓸 위험

이 문제를 Runtime 기능과 Compile-time 검증으로 분리해 해결했습니다.

## 구성

### `mybatis-easy-core`

실행 시점에 공통 MyBatis 기능을 제공합니다.

- `BaseMapper<T, ID>` 기반 기본 CRUD 제공
- 사용자가 작성한 XML statement가 있으면 자동 생성 SQL보다 우선
- `@Table`, `@Id`, `@Column`, `@SoftDelete` 기반 매핑
- `NamingStrategy`를 통한 camelCase ↔ snake_case 대응
- JDBC metadata 기반 개발용 EntityGenerator

### `mybatis-easy-processor`

Java Annotation Processor로 Mapper 인터페이스와 XML을 비교합니다.

다음 문제를 빌드 시점에 확인합니다.

- Mapper 메서드는 있지만 XML statement가 없는 경우
- XML에는 있지만 Mapper에서 제거된 statement
- 같은 XML id를 만들 수 있는 Mapper 메서드 오버로딩

선택적으로 누락된 statement stub을 생성할 수 있지만, 기존 XML 전체를 다시 쓰지 않고 관리 영역 안에서만 수정합니다.

## 주요 설계 판단

### 1. 사용자 SQL을 우선합니다

자동 생성 CRUD는 기본값일 뿐입니다.

같은 statement id가 XML에 존재하면 사용자가 직접 작성한 SQL을 유지합니다.  
성능 최적화 쿼리나 복잡한 도메인 SQL을 자동화 기능 때문에 포기하지 않도록 했습니다.

### 2. 파괴적인 변경은 자동 적용하지 않습니다

DB 컬럼 추가는 비교적 안전하지만 삭제, 타입 변경, 이름 변경은 영향 범위가 큽니다.

따라서 EntityGenerator는 위험한 변경을 임의로 적용하기보다 개발자가 판단할 수 있도록 힌트를 남기는 방향으로 설계했습니다.

### 3. Runtime 자동화와 Compile-time 검증을 분리했습니다

역할을 다음과 같이 분리했습니다.

- Runtime: CRUD SQL 제공
- Development-time: DB/Entity 변경 보조
- Compile-time: Mapper/XML 일치 여부 검증

각 기능을 독립적으로 비활성화하거나 검증할 수 있도록 한 구조입니다.

## 사용 예시

```java
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id")
    private Long id;

    private String name;

    @SoftDelete
    private LocalDateTime deletedAt;
}
```

```java
@Mapper
public interface UserMapper extends BaseMapper<User, Long> {
    // 기본 CRUD는 자동 제공
    // 커스텀 SQL이 필요한 경우 기존 MyBatis XML 사용
}
```

## BaseMapper

```java
int insert(Object entity);
Optional<T> findById(ID id);
List<T> findAll();
List<T> findPage(long offset, int limit);
long countAll();
int update(Object entity);
int deleteById(ID id);
```

## Processor 설정

```text
-Ames.xmlDir=src/main/resources/mapper
-Ames.failOnMissing=true
-Ames.failOnOrphan=false
-Ames.generateMissing=false
-Ames.debug=false
```

기본 정책은 보수적으로 설정했습니다.

- missing statement 발견 시 빌드 실패 가능
- orphan statement는 별도 감지
- 자동 stub 생성은 전용 관리 영역에서만 수행
- 기존 SQL statement를 자동 삭제하지 않음

## 프로젝트 구조

```text
mybatis-easy-sync-starter/
├── mybatis-easy-core/
│   ├── core/annotation
│   ├── core/mapper
│   ├── support
│   └── tool/generator
└── mybatis-easy-processor/
    ├── scan
    ├── validate
    ├── generate
    └── util
```

## 빌드

요구사항:

- Java 17+
- Gradle

```bash
./gradlew clean test
```

## 범위

이 프로젝트는 MyBatis와 직접 작성한 SQL을 유지하면서 반복적인 CRUD와 Mapper/XML 관리 비용을 줄이고 싶은 경우를 대상으로 합니다.

JPA의 영속성 컨텍스트나 Entity lifecycle, Query DSL을 대체하는 것을 목표로 하지 않습니다.
