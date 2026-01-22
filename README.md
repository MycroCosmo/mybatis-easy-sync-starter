# MyBatis Easy Starter

MyBatis + Spring Boot 환경에서  
**기본 CRUD SQL 자동 생성**, **DTO 파라미터 자동 매핑**,  
**개발 환경용 Entity/DB 동기화 보조**를 제공하는 생산성 향상 라이브러리입니다.

> ⚠️ 이 라이브러리는 **기존 MyBatis 사용 방식을 대체하지 않습니다.**  
> XML, Mapper, 커스텀 쿼리는 그대로 유지하면서  
> **반복적인 기본 CRUD만 자동화**하는 것을 목표로 합니다.

---

## 왜 이 라이브러리를 만들었는가

JPA는 제가 판단했을 때 **대규모 프로젝트에 적합한 기술**이라고 생각했습니다.  
반면, 중·소규모 프로젝트나 개인 프로젝트에서는 다음과 같은 부담이 있었습니다.

- 연관관계 설계와 영속성 컨텍스트 관리 비용
- 단순 CRUD에도 과한 설정
- QueryDSL 도입 전제
- SQL을 의도적으로 직접 제어하기 어려운 구조

그래서 MyBatis로 프로젝트를 진행했지만, 실제 사용 과정에서 다음 문제를 겪었습니다.

- 테이블마다 반복되는 기본 CRUD SQL 작성
- DB 스키마 변경 시 VO에서 이를 알 수 없음
- VO / Mapper XML 생성과 관리가 전부 수동

이 라이브러리는 다음 목표를 가지고 만들어졌습니다.

> **JPA처럼 기본 CRUD는 자동으로 제공하되,  
> MyBatis의 장점인 ‘직접 SQL 작성’은 그대로 유지한다.**

- 기본 CRUD는 자동
- DB 변경은 VO에서 즉시 인지
- 나머지 쿼리는 MyBatis XML에 직접 작성
- QueryDSL 같은 추가 도구는 필요 없음

---

## 주요 기능

### 1. 기본 CRUD SQL 자동 생성 (Runtime)

- `BaseMapper<T, ID>`를 상속한 Mapper에 대해 자동 제공
  - `insert`
  - `findById`
  - `findAll`
  - `update`
  - `deleteById`
- **이미 XML에 정의된 SQL은 자동 생성 대상에서 제외**
- Mapper XML 파일을 직접 수정하지 않고  
  **실행 시점에 가상 리소스로 CRUD SQL만 병합**

```java
public interface UserMapper extends BaseMapper<User, Long> {
}
```

### 2. Soft Delete 자동 처리
엔티티에 `@SoftDelete` 필드가 존재하면 별도의 SQL 수정 없이 논리 삭제 로직이 적용됩니다.

* **삭제 로직:** `deleteById()` 호출 시 실제 `DELETE` 쿼리 대신 `UPDATE` 쿼리가 실행되어 삭제 일시나 플래그를 변경합니다.
* **조회 로직:** `findById`, `findAll` 등 모든 조회 쿼리 실행 시 자동으로 `WHERE ... AND deleted_at IS NULL`과 같은 조건이 추가됩니다.
* **유연한 설정:** 어노테이션 옵션을 통해 특정 상황에서만 물리 삭제(Hard Delete)를 수행하도록 설정할 수 있습니다.

### 3. DTO 파라미터 자동 매핑
Mapper 메서드 파라미터로 DTO 또는 VO를 전달할 때 번거로운 매핑 과정을 생략합니다.

* 내부적으로 `DTO` -> `Map<String, Object>` 자동 변환을 지원합니다.
* MyBatis XML 내에서 별도의 파라미터 타입 설정 없이 컬럼명 기준으로 데이터에 접근할 수 있습니다.

```java
// 별도의 Map 변환 없이 DTO를 바로 전달
userMapper.insert(userDto);
```
### 4. @Column 기반 컬럼 매핑
DTO/VO의 필드명과 데이터베이스의 컬럼명이 일치하지 않더라도 정확한 매핑을 지원합니다.

* **NamingStrategy 지원:** 별도의 설정 없이도 `camelCase` 필드명을 `snake_case` 컬럼명으로 자동 매핑합니다.
* **명시적 매핑:** `@Column(name = "...")` 어노테이션을 사용하여 특정 컬럼명을 직접 지정할 수 있습니다.

```java
@Column(name = "member_name")
private String name;
```

### 5. NamingStrategy 확장 포인트
테이블명과 컬럼명 생성 로직을 프로젝트 표준에 맞게 확장할 수 있는 유연한 구조를 제공합니다.

* **기본 전략:** `camelCase` 필드명을 `snake_case` 컬럼명으로 자동 변환합니다.
* **사용자 정의 전략:** `NamingStrategy` 인터페이스를 구현한 뒤 `@Bean`으로 등록하면, 라이브러리 내부 로직이 해당 전략을 우선적으로 사용합니다.

```java
@Bean
public NamingStrategy namingStrategy() {
    // 프로젝트 고유의 네이밍 규칙(예: 접두어 추가 등)을 적용한 전략 주입
    return new CustomNamingStrategy();
}
```

### 6. EntityGenerator (개발 환경 전용)
> ⚠️ **중요:** 운영 환경(Production) 사용 금지. 실행 시 로컬 소스 코드를 직접 수정합니다.

DB 스키마 변경 사항을 실시간으로 감지하여 소스 코드에 반영함으로써, VO와 XML을 수동으로 관리하는 번거로움을 제거합니다.

* **변경 사항 자동 동기화:** DB에 컬럼이 추가되면 필드를 자동 생성하고 관련 주석을 추가합니다.
* **상태 힌트 제공:** * 컬럼 삭제 시: `[DELETED FROM DB]` 주석 처리
  * 타입 불일치 시: `[Type Mismatch]` 알림 주석
  * 이름 변경 추정 시: `[RENAMED?]` 힌트 주석 추가
* **설정 방식:** `application.yml`에서 활성화해야만 동작하며, `src/main/java` 및 `src/main/resources` 경로의 파일을 직접 수정합니다.

```yaml
mybatis-easy:
  generator:
    enabled: true
    use-db-folder: true # mapper/{dbName}/ 구조 사용 여부
```

## 🛠 핵심 구조 및 동작 원리

이 라이브러리는 두 가지 핵심 기능이 목적과 작동 시점에 따라 완전히 분리되어 설계되었습니다.

| 기능 | 역할 | 특징 |
| :--- | :--- | :--- |
| **AutoSql** | 기본 CRUD SQL 자동 주입 | 런타임에 가상 리소스로 병합 (운영 환경 사용 가능) |
| **EntityGenerator** | VO / Mapper XML 생성 및 동기화 | 로컬 파일 시스템(Java, XML) 직접 수정 (개발 전용) |

### ⚙️ 기능 활성화 매트릭스
`BaseMapper`를 통한 기본 CRUD 기능은 `autosql.enabled = true` 설정 시에만 작동합니다.

| autosql | generator | CRUD 동작 여부 | 비고 |
| :---: | :---: | :---: | :--- |
| **true** | **true** | ✅ 동작 | 개발 초기: 코드 생성과 CRUD 동시 사용 |
| **true** | false | ✅ 동작 | 일반 개발 및 운영: 안정적인 CRUD 제공 |
| false | **true** | ❌ 불가 | 제너레이터만 작동 (CRUD는 직접 작성 필요) |
| false | false | ❌ 불가 | 모든 기능 비활성화 |

---

#### 🔍 상세 동작 예시

**1) DB 컬럼이 추가되었을 때 (자동 반영)**
기존 필드 구조를 침범하지 않고, 지정된 영역(`ADDED FIELDS`) 내에만 필드를 추가합니다.
```java
public class User {
    private Long id;
    private String name;

    // MyBatis-Easy: ADDED FIELDS BEGIN
    private String nickname; // Added from DB column 'nickname'
    // MyBatis-Easy: ADDED FIELDS END
}
```

**2) DB 컬럼이 삭제되었을 때 (삭제 힌트)**
DB에서 컬럼이 삭제되어도 VO의 필드를 자동으로 제거하지 않습니다. 대신 해당 필드 위에 삭제되었음을 알리는 주석을 추가하여 기존 로직의 컴파일 에러를 방지하고 개발자가 인지하도록 돕습니다.

* 예: DB에서 `age` 컬럼이 삭제된 경우
```java
public class User {
    private Long id;
    private String name;

    // [DELETED FROM DB] Column 'age' no longer exists in table USER
    private Integer age;
}
```

**3) DB 컬럼 타입이 변경되었을 때 (타입 경고)**
DB 컬럼 타입이 변경되면 VO의 타입을 강제로 바꾸지 않고, 타입 불일치 경고 주석을 추가합니다. 개발자는 이 힌트를 보고 직접 VO 타입 수정 여부를 결정합니다.

* 예: DB `point` 컬럼이 `Integer`로 변경되었으나 VO는 `Long`인 경우
```java
public class User {
    private Long id;

    // [Type Mismatch] DB type: Integer (Current: Long)
    private Long point;
}
```
**4) DB 컬럼명이 변경되었을 가능성이 있을 때 (이름 변경 추정)**
DB에서 특정 컬럼이 사라지고 유사한 이름의 신규 컬럼이 생기면, EntityGenerator가 유사도를 기반으로 "이름 변경 가능성"을 추정하여 힌트 주석을 추가합니다.

* 예: DB에서 `phoneNumber`가 사라지고 `mobile_number`가 생긴 경우 (추정)
```java
public class User {
    private Long id;

    // [RENAMED?] This field may have been renamed to column 'mobile_number' (field: mobileNumber)
    private String phoneNumber;
}
```

> 💡 이 기능은 "정답 확정"이 아니라 "**정리 힌트**"입니다. 실제 이름 변경 여부 및 대응은 개발자가 최종적으로 결정합니다.

---

## 🎯 핵심 설계 원칙

1. **자동 추가:** 추가(생성)는 파괴적이지 않고 안전하므로 **자동 반영**을 수행합니다.
2. **주석 힌트:** 삭제, 타입 변경, 이름 변경은 시스템 파급력이 크므로 **주석 힌트**만 제공합니다.
3. **개발자 제어권:** 최종 제어권은 항상 개발자에게 있으며, 라이브러리는 판단을 돕는 가이드 역할만 수행합니다.

---

## 🚀 권장 사용 흐름

1. **초기 구축 (개발 환경):**
   `autosql`과 `generator`를 모두 활성화(`true`)하여 DB 기준의 VO와 Mapper XML 뼈대를 생성하고 Git에 커밋합니다.
2. **이후 개발 및 운영 환경:**
   `generator.enabled: false`로 설정합니다. 기본 CRUD는 계속 제공되면서, 로컬 파일이 의도치 않게 수정되는 것을 방지하여 운영 안정성을 확보합니다.

---

## 📅 라이브러리 고도화 TODO (Roadmap)

지속적인 업데이트를 통해 다음과 같은 기능들이 추가될 예정입니다.

* [ ] **페이지네이션(Pagination):** 효율적인 조회를 위한 공통 페이징 로직 추가
* [ ] **데이터 정합성:** DB와 엔티티 간 데이터 유효성 검증 레이어 강화
* [ ] **로그 및 설정 유연화:** 자동 생성 쿼리의 로그 출력 제어 및 상세 설정 지원
* [ ] **Soft Delete 옵션화:** 상황에 따라 하드 삭제(Hard Delete)를 선택할 수 있는 어노테이션 옵션 추가
* [ ] **공통 필드 자동 주입:** 등록일/수정일 등 공통 필드 주입 라이브러리 고도화
* [ ] **VO 생성 필터링:** 특정 테이블만 선택하여 VO를 생성할 수 있는 필터 기능 추가
* [ ] **공통 검색 Wrapper:** 복잡한 검색 조건을 위한 Wrapper 및 자동 동적 SQL 생성 지원

---

## ⚠️ 환경별 사용 가이드 요약

| 기능 | 개발 환경 | 운영 환경 | 비고 |
| :--- | :---: | :---: | :--- |
| CRUD 자동 생성 (AutoSql) | ✅ 권장 | ✅ 권장 | `autosql.enabled: true` |
| DTO 파라미터 매핑 | ✅ 가능 | ✅ 가능 | |
| Soft Delete 처리 | ✅ 가능 | ✅ 가능 | |
| **EntityGenerator (코드 수정)** | ✅ **선택** | ❌ **금지** | 로컬 코드 오염 방지 |

---

## 📦 설치 및 설정 (JitPack)

### Gradle
```gradle
repositories {
    maven { url '[https://jitpack.io](https://jitpack.io)' }
}

dependencies {
    implementation 'com.github.MycroCosmo:mybatis-easy-starter:v1.0.0'
}
```

## 🚀 Usage Example

라이브러리를 적용하는 방법은 매우 간단합니다. 엔티티(Entity)를 정의하고 이를 관리할 매퍼(Mapper) 인터페이스를 만들면 됩니다.

### 1. Entity 정의
`@Table`, `@Id`, `@Column` 등의 어노테이션을 사용하여 DB 테이블과 매핑합니다.

```java
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id")
    private Long id;

    private String name;

    private String email;

    @SoftDelete
    private LocalDateTime deletedAt; // 논리 삭제 필드
}
```

### 2. Mapper 정의
`BaseMapper<T, ID>` 인터페이스를 상속받으면, 별도의 XML 작성 없이도 기본 CRUD 메서드가 런타임에 자동으로 주입됩니다.

```java
@Mapper
public interface UserMapper extends BaseMapper<User, Long> {
    // insert, findById, findAll, update, deleteById를 구현 없이 바로 사용 가능
}
```

## 📝 Configuration (application.yml)

프로젝트 환경에 맞춰 기능을 세부적으로 제어할 수 있습니다.

### 기본 CRUD 및 자동 매핑 설정
```yaml
mybatis-easy:
  autosql:
    enabled: true        # 기본값: true (런타임 시 CRUD SQL 자동 주입)
```

* **참고:** 이미 XML에 수동으로 작성된 ID의 쿼리가 있다면, 해당 쿼리는 자동 생성 대상에서 제외되어 기존 커스텀 로직을 보호합니다.

### Generator 설정 (개발 환경 전용)
```yaml
mybatis-easy:
  generator:
    enabled: true        # 디자인 타임 소스 코드 생성 및 DB 동기화 활성화
    use-db-folder: true  # mapper/{dbName}/ 하위 폴더 구조 사용 여부
```

* ** 경고:** 로컬 소스 코드(src/main/java 등)를 직접 수정하므로, 운영 환경에서는 반드시 `false`로 설정하십시오.


---

## MES Processor (컴파일 타임 XML 검증/보조 생성)

이 프로젝트에는 **컴파일 타임(Annotation Processor)** 에 동작하는 `mes-processor`가 포함되어 있습니다.  
`@Mapper` 인터페이스와 Mapper XML을 스캔하여 다음을 수행합니다.

- **Missing 검사:** Mapper 메서드는 있는데 XML에 id가 없는 경우 감지
- **Orphan 검사:** XML에는 있는데 Mapper 메서드가 사라진 id 감지
- **선택적 보조 생성:** missing id에 대해 XML에 stub 블록을 자동 추가(옵션)
- **Orphan 주석 처리:** orphan id에 대해 statement 바로 위에 “ORPHAN” 주석 삽입(옵션)

> 주의: processor는 **기존 XML을 통째로 생성/재정렬하지 않습니다.**  
> 오직 `<!-- MES-AUTO-GENERATED:SECTION-BEGIN -->` ~ `<!-- MES-AUTO-GENERATED:SECTION-END -->`  
> 구간만 안전하게 수정합니다.

### 동작 정책

- **flat-only 정책:** `mes.xmlDir` 바로 아래의 `*.xml`만 스캔합니다(하위 폴더 walk 없음)
- **재현성 보장:** 파일/namespace/statement id 처리 순서를 고정하여 빌드 결과가 흔들리지 않도록 합니다
- **안전한 파싱:** MyBatis XML의 `DOCTYPE` / 외부 엔티티 로딩을 차단하여 빌드 환경에 따른 파싱 실패를 줄입니다

### Processor Options

| 옵션 | 기본값 | 설명 |
|---|---:|---|
| `mes.xmlDir` | `src/main/resources/mapper` | Mapper XML 디렉터리 (flat-only) |
| `mes.failOnMissing` | `true` | missing 발견 시 빌드 실패 처리 |
| `mes.failOnOrphan` | `false` | orphan 발견 시 빌드 실패 처리 |
| `mes.generateMissing` | `false` | missing stub 자동 생성/추가 |
| `mes.debug` | `false` | 디버그 로그 출력 |

### XML 자동 생성/수정 규칙

- 섹션이 없으면 `</mapper>` 직전에 아래 마커를 자동 삽입합니다.
  - `<!-- MES-AUTO-GENERATED:SECTION-BEGIN -->`
  - `<!-- MES-AUTO-GENERATED:SECTION-END -->`
- missing id는 **END 직전**에 stub로 append 됩니다.
- orphan id는 해당 statement 시작 태그 라인 바로 위에 주석만 추가합니다.
- 기존 statement의 내용/순서/서식은 가능한 한 유지합니다.

## 📦 설치 및 설정 (JitPack)

### Gradle
```gradle
### Gradle (Java)

repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.MycroCosmo:mybatis-easy-starter:v1.0.0'

    // MES Processor (compile-time)
    annotationProcessor 'com.github.MycroCosmo:mybatis-easy-processor:v1.0.0'
}

```

```build.gradle
### MES Processor Options (build.gradle)

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += listOf(
        "-Ames.xmlDir=src/main/resources/mapper",
        "-Ames.failOnMissing=true",
        "-Ames.failOnOrphan=false",
        "-Ames.generateMissing=false",
        "-Ames.debug=false"
    )
}

```

---

## 📌 MES Processor 사용 예시

### 1) 기본 상황: Mapper와 XML이 일치하는 경우 (정상)

#### Mapper 인터페이스
```java
@Mapper
public interface PostMapper {

    Post findById(Long id);

    void save(Post post);
}
```
#### Mapper XML 
```xml
<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <insert id="save">
        INSERT INTO post(title, content) VALUES(#{title}, #{content})
    </insert>
</mapper>
```

#### 결과:
- `missing = {}`
- `orphan = {}`
- 빌드 통과, XML 수정 없음

---

### 2) Missing 발생 예시 (Mapper에 있는데 XML에 없음)
#### Mapper 인터페이스
``` java
@Mapper
public interface PostMapper {

    Post findById(Long id);

    void save(Post post);

    void deleteById(Long id);   // 새로 추가된 메서드
}
```
#### 기존 XML (아직 수정 안 됨)
``` xml
<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <insert id="save">
        INSERT INTO post(title, content) VALUES(#{title}, #{content})
    </insert>

</mapper>
```

#### 빌드 로그 (기본 설정)
``` markdown
WARNING: MES missing:
- com.example.mapper.PostMapper
  * deleteById

```

#### 옵션 mes.generateMissing=true 설정 시 자동으로 XML에 추가됨
``` xml
<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <insert id="save">
        INSERT INTO post(title, content) VALUES(#{title}, #{content})
    </insert>

    <!-- MES-AUTO-GENERATED:SECTION-BEGIN -->

        <delete id="deleteById">
          /* TODO: write SQL */
        </delete>

    <!-- MES-AUTO-GENERATED:SECTION-END -->

</mapper>
```

#### 특징
- 기존 쿼리는 건드리지 않음
- 자동 생성은 반드시 MES 섹션 내부에서만 수행

---
### 3) Orphan 발생 예시 (XML에는 있는데 Mapper에 없음)

#### Mapper (메서드 삭제됨)
``` java
@Mapper
public interface PostMapper {

    Post findById(Long id);
}
```

#### 기존 XML
``` xml
<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <delete id="deleteById">
        DELETE FROM post WHERE id = #{id}
    </delete>

</mapper>
```

#### 빌드 로그

``` markdown
WARNING: MES orphan:
- com.example.mapper.PostMapper
  * deleteById
```

#### `mes.generateMissing=true` + orphan 존재 시 자동 주석 삽입

``` xml

<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <!-- MES-ORPHAN: id=deleteById no longer exists in mapper interface -->
    <delete id="deleteById">
        DELETE FROM post WHERE id = #{id}
    </delete>

</mapper>

```

#### 정책

 - orphan은 절대 삭제하지 않음
 - "대신 “이 메서드는 더 이상 Mapper에 없다"는 명확한 주석 힌트 제공

---
### 4) Missing + Orphan이 동시에 존재하는 경우
#### Mapper
``` java
@Mapper
public interface PostMapper {

    Post findById(Long id);

    void save(Post post);      // 추가됨
}
```

#### 기존 XML
``` xml
<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <delete id="deleteById">
        DELETE FROM post WHERE id = #{id}
    </delete>

</mapper>

```

#### 자동 처리 결과 (`mes.generateMissing=true`)

``` xml

<mapper namespace="com.example.mapper.PostMapper">

    <select id="findById" resultType="com.example.domain.Post">
        SELECT * FROM post WHERE id = #{id}
    </select>

    <!-- MES-AUTO-GENERATED:SECTION-BEGIN -->

        <insert id="save">
          /* TODO: write SQL */
        </insert>

    <!-- MES-AUTO-GENERATED:SECTION-END -->

    <!-- MES-ORPHAN: id=deleteById no longer exists in mapper interface -->
    <delete id="deleteById">
        DELETE FROM post WHERE id = #{id}
    </delete>

</mapper>
```

#### 요점
- missing → 섹션 내부에 stub 추가
- orphan → 기존 위치에 주석 추가
- 두 작업은 서로 충돌하지 않음

---
### 5) 빌드 옵션 권장 세팅 (실전용)

``` gradle
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += [
        "-Ames.xmlDir=src/main/resources/mapper",
        "-Ames.failOnMissing=true",
        "-Ames.failOnOrphan=false",
        "-Ames.generateMissing=true",
        "-Ames.debug=false"
    ]
}

```

#### 권장 조합

| 환경    | failOnMissing | failOnOrphan | generateMissing |
| ----- | ------------- | ------------ | --------------- |
| 개발 초기 | true          | false        | true            |
| 일반 개발 | true          | false        | false           |
| CI    | true          | true         | false           |

