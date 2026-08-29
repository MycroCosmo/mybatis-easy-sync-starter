# MyBatis Easy Starter

MyBatis의 SQL 제어권은 유지하면서 반복 CRUD와 Mapper XML 불일치를 줄이기 위해 만든 Java 17 기반 개인 프로젝트입니다.

## 해결하려는 문제

- 테이블마다 같은 CRUD XML을 반복 작성해야 합니다.
- 엔티티와 Mapper XML이 따로 변경되면 누락된 statement를 빌드 전에 발견하기 어렵습니다.
- DB schema 변경을 Java 소스에 반영하는 과정이 수동입니다.

이 프로젝트는 커스텀 SQL을 대체하지 않습니다. 기본 CRUD만 자동화하고, 사용자가 XML에 작성한 statement를 우선합니다.

## Architecture

```text
consumer application
 ├─ mybatis-easy-core
 │   ├─ AutoSqlBuilder          entity metadata → CRUD SQL
 │   ├─ AutoConfiguration       generated SQL + user XML merge
 │   ├─ ParameterInterceptor    DTO → MyBatis parameter mapping
 │   └─ EntityGenerator         DB metadata → Java source (development only)
 └─ mybatis-easy-processor
     └─ MesProcessor            mapper method ↔ XML statement validation
```

런타임 SQL 생성과 컴파일 타임 XML 검증을 별도 module로 분리했습니다. core만 사용하는 소비자가 processor를 암묵적으로 로딩하지 않으며, processor가 필요한 프로젝트만 annotation processor로 추가할 수 있습니다.

## 주요 기술

- Java 17, Gradle multi-module
- Spring Boot auto-configuration, MyBatis, JDBC metadata
- Java annotation processing API
- JUnit 5, JavaCompiler API

## 핵심 기술적 문제와 해결

### 1. 배포 JAR는 빌드되지만 소비자 컴파일은 실패

core JAR에 존재하지 않는 annotation processor provider가 등록돼 빈 소비자 소스도 컴파일되지 않았습니다. 잘못된 service provider 파일을 제거하고 processor 등록 책임을 processor module로 한정했습니다.

회귀 테스트는 저장소 내부 classpath가 아니라 실제 core runtime classpath로 별도 소비자 소스를 컴파일합니다.

### 2. 생성 코드가 선언되지 않은 Lombok에 의존

EntityGenerator가 `@Data`를 생성했지만 Lombok은 소비자 필수 dependency가 아니었습니다. 생성 결과를 표준 getter/setter로 바꾸고, 최초 생성 및 컬럼 추가 동기화 후 소스를 JavaCompiler로 다시 컴파일합니다.

### 3. SQL 생성 오류가 빈 문자열로 지연

metadata 분석 실패를 빈 SQL로 숨기면 실제 원인은 애플리케이션 시작 이후 MyBatis statement 오류로 나타납니다. 생성 실패를 원인 예외와 entity 이름을 포함한 `IllegalStateException`으로 즉시 전달하도록 변경했습니다.

### 4. pagination 경계값

최대 page size만 제한하던 SQL에 음수 offset과 0 이하 limit의 하한을 추가했습니다. MySQL 계열과 ANSI `OFFSET/FETCH` 계열의 생성 결과, 사용자 정의 statement 우선 정책을 단위 테스트로 고정했습니다.

## 동작 범위

- `BaseMapper<T, ID>`의 insert/findById/findAll/findPage/countAll/update/deleteById 생성
- 사용자 XML에 같은 id가 있으면 자동 SQL 생성 제외
- PostgreSQL, MySQL/MariaDB, H2, SQLite, SQL Server, Oracle별 pagination 분기
- identifier quoting과 generated-key strategy 설정
- `@SoftDelete` 필드는 현재 timestamp/nullable column 방식만 지원
- EntityGenerator는 로컬 개발 환경에서만 명시적으로 활성화

## 최소 사용 예시

```java
@Table(name = "users")
public class User {
    @Id
    private Long id;
    private String name;
}

public interface UserMapper extends BaseMapper<User, Long> {
}
```

```yaml
mybatis-easy:
  auto-sql:
    enabled: true
  pagination:
    enabled: true
    dialect: POSTGRES
    max-page-size: 200
  generator:
    enabled: false
    allow-write: false
```

AutoSQL과 generator는 기본적으로 비활성화됩니다. generator는 `enabled=true`와 `allow-write=true`가 모두 설정된 로컬 개발 환경에서만 파일을 수정합니다.

## 테스트 전략

- 실제 core JAR 소비자 컴파일
- DB metadata mock을 이용한 entity 생성·재동기화·생성 소스 컴파일
- dialect별 pagination과 soft-delete SQL 계약
- 사용자 XML statement 보존
- 잘못된 metadata의 fail-fast 동작

```bash
./gradlew clean test --no-daemon
```

## 현재 한계

- 각 dialect의 SQL 문자열 생성은 검증하지만 모든 DB에 대한 실DB 통합 테스트는 아직 없습니다.
- `@SoftDelete`의 `deletedValue`/`notDeletedValue` 옵션은 현재 SQL에 반영되지 않으므로 timestamp/NULL 방식만 문서상 지원합니다.
- 지원하지 않는 cascade delete나 분산 transaction 기능을 제공한다고 주장하지 않습니다.

## 결과와 배운 점

라이브러리 자체 build 성공만으로 소비자 사용 가능성을 보장할 수 없었습니다. 배포 산출물과 생성 소스를 실제 소비자 관점에서 컴파일하는 테스트가 module 경계와 공개 계약을 검증하는 데 더 효과적이었습니다.
