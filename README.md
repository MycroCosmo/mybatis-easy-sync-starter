# MyBatis Easy Sync Starter

MyBatis에서 반복되는 CRUD 작성과 Mapper/XML 불일치 확인을 돕는 Java 17 기반 라이브러리입니다. 직접 작성한 SQL을 유지하면서 반복 작업만 자동화하는 것이 목표입니다.

## 모듈

| 모듈 | 역할 |
| --- | --- |
| `mybatis-easy-core` | 기본 CRUD SQL 병합, 필드·컬럼 매핑, 개발용 엔티티 생성 |
| `mybatis-easy-processor` | 컴파일 시 Mapper 메서드와 XML statement ID 비교, 선택적 stub 생성 |

런타임 SQL 병합과 컴파일 시점 검증은 별도 기능입니다. Processor가 생성하는 stub은 완성된 업무 SQL이 아닙니다.

## 설치 — 로컬 Maven 저장소 기준

JDK 17과 저장소에 포함된 Gradle Wrapper를 준비합니다. 현재 빌드 파일에서 core 버전은 `1.0.2`, processor 버전은 `1.0.0`으로 서로 다릅니다. 아래는 공개 패키지 저장소의 배포 성공을 전제하지 않는 로컬 설치 절차입니다.

라이브러리 저장소 루트:

```bash
./gradlew :mybatis-easy-core:publishToMavenLocal :mybatis-easy-processor:publishToMavenLocal
```

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

소비자 Spring Boot 프로젝트의 `build.gradle`:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.thenoah.dev:mybatis-easy-core:1.0.2'
    // Mapper/XML 컴파일 시점 검증이 필요한 경우에만 추가
    annotationProcessor 'com.thenoah.dev:mybatis-easy-processor:1.0.0'
    runtimeOnly 'org.postgresql:postgresql'
}
```

소비자 프로젝트는 Spring Boot 3.x와 Java 17 환경을 기준으로 구성합니다. 사용하는 DB의 JDBC 드라이버와 DataSource 설정도 필요합니다.

## AutoSQL 활성화에 필요한 조건

**`BaseMapper` 상속만으로 동작하지 않습니다.** 현재 자동 구성은 `mybatis-easy.autosql.enabled=true`일 때 생성되며, 설정이 없으면 활성화되지 않습니다. 또한 읽을 수 있는 Mapper XML 리소스가 없으면 병합을 종료합니다.

소비자 프로젝트의 `application.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

mybatis:
  mapper-locations: classpath*:mapper/**/*.xml

mybatis-easy:
  autosql:
    enabled: true
  generator:
    enabled: false
    allow-write: false
```

`DB_URL`에는 개발 DB의 JDBC URL을 설정합니다. `generator`를 끈 상태에서는 Java 소스나 DB 테이블을 자동으로 만들어 주지 않으므로 엔티티와 테이블은 직접 준비해야 합니다.

### 엔티티와 Mapper 예시

```java
package com.example.demo;

import com.thenoah.dev.mybatis_easy_starter.core.annotation.Column;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Id;
import com.thenoah.dev.mybatis_easy_starter.core.annotation.Table;

@Table(name = "users")
public class User {
    @Id
    @Column(name = "user_id")
    private Long id;
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
```

```java
package com.example.demo;

import com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User, Long> {
}
```

### 반드시 준비할 XML 리소스

경로: `src/main/resources/mapper/UserMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.demo.UserMapper">
</mapper>
```

`namespace`는 실제 Mapper의 전체 클래스명과 일치해야 합니다. 자동 구성은 XML 리소스를 찾아 `BaseMapper`와 연결한 뒤 필요한 SQL을 가상 리소스에 병합합니다. 커스텀 statement를 XML에 작성하면 동일 ID의 자동 생성 SQL보다 우선합니다.

## 제공 API

```java
int insert(Object entity);
Optional<T> findById(ID id);
List<T> findAll();
List<T> findPage(long offset, int limit);
long countAll();
int update(Object entity);
int deleteById(ID id);
```

이 블록은 인터페이스 시그니처 요약입니다. 결과가 커질 수 있는 테이블에서는 무제한 `findAll()` 대신 페이지 조회를 사용하세요. DB별 SQL 호환성과 사용자 XML 우선 정책은 소비자 프로젝트에서 검증해야 합니다.

## 선택 사항: Mapper/XML 컴파일 검증

Processor는 missing statement, orphan statement, XML ID와 충돌하는 메서드 오버로딩을 검사합니다.

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += [
        '-Ames.xmlDir=src/main/resources/mapper',
        '-Ames.failOnMissing=true',
        '-Ames.failOnOrphan=false',
        '-Ames.generateMissing=false',
        '-Ames.debug=false'
    ]
}
```

런타임 AutoSQL 생성과 Processor의 XML 검사는 실행 시점이 다릅니다. Processor 설정을 추가할 때는 자신의 Mapper 구성에서 missing 판정 범위를 확인하세요. 자동 stub 생성은 관리 영역을 사용하지만 SQL 완성을 대신하지 않습니다.

## 개발용 엔티티 생성기

JDBC 메타데이터를 이용한 소스 생성기는 로컬 파일을 수정합니다. 현재 자동 구성은 `generator.enabled`, `generator.allow-write`, 개발 환경 판정을 함께 확인합니다. 운영에서는 비활성화하고, 로컬에서 켜더라도 생성 전후 Git diff를 검토하세요.

## 검증 범위

```bash
./gradlew test
```

테스트 실행 결과와 실제 소비자 프로젝트의 컴파일·DB CRUD 결과는 구분해야 합니다. 이 README는 설치·활성화에 필요한 조건을 설명하며, 모든 DB·Spring Boot 조합의 호환성을 보장하지 않습니다. JPA의 영속성 컨텍스트나 엔티티 생명주기를 제공하는 ORM을 목표로 하지 않습니다.
