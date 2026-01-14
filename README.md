# MyBatis Easy Starter

A Spring Boot Starter for intelligent CRUD automation and seamless DTO-VO mapping with Soft Delete support.

## Features
* **Auto CRUD Generation**: Automatically generates basic CRUD (Insert, Select, Update, Delete) at runtime without XML mapping.
* **Intelligent Delete Policy**: Automatically detects `@SoftDelete` and toggles between physical `DELETE` and logical `UPDATE`.
* **DTO Friendly Mapping**: Seamlessly binds DTOs to database columns using `@Column` annotations even when field names differ.
* **Automatic FindAll Filtering**: Automatically excludes records marked as deleted when using `findAll()`.

## Requirements
* Java 17 or higher
* Spring Boot 3.x
* MyBatis 3.x

## Installation (JitPack)
Add the repository to your `build.gradle`:
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.MycroCosmo:mybatis-easy-sync-starter:v0.0.1'
}

```
## Usage
Simply extend the base interface or use the provided annotations on your Entity classes.

```java
@Table(name = "users")
public class UserVO {
    @Id
    @Column(name = "user_id")
    private Long id;
    
    // Automatically filtered in findAll() if @SoftDelete is present
    @SoftDelete
    private LocalDateTime deletedAt;
}

```

---

# MyBatis Easy Starter
MyBatis + Spring Boot 환경에서 **기본 CRUD SQL 자동 생성**, **DTO 파라미터 자동 매핑**,  
**개발 환경용 Entity/DB 동기화 보조**를 제공하는 생산성 향상 라이브러리입니다.

> ⚠️ 이 라이브러리는 **기존 MyBatis 사용 방식을 대체하지 않습니다.**  
> XML, Mapper, 커스텀 쿼리는 그대로 유지하면서  
> “반복적인 기본 CRUD만” 자동화하는 것을 목표로 합니다.

---
## 주요기능
### 1. 기본 CRUD SQL 자동 생성 (Runtime)
- `BaseMapper<T, ID>`를 상속한 Mapper에 대해
  - `insert`
  - `findById`
  - `findAll`
  - `update`
  - `deleteById`
- **이미 XML에 정의된 SQL은 자동 생성 대상에서 제외**
- mapper XML을 **직접 수정하지 않고**,  
  실행 시점에 가상 리소스로 CRUD SQL만 병합
---

### 2. Soft Delete 자동 처리
- 엔티티에 `@SoftDelete` 필드가 존재하면
  - `deleteById()` → `DELETE` 대신 `UPDATE`
  - 조회 시 자동으로 `IS NULL` 조건 추가
- 논리 삭제 / 물리 삭제를 **코드 변경 없이 자동 전환**

---
### 3. DTO 파라미터 자동 매핑
- Mapper 메서드 파라미터로 DTO/VO 전달 가능
- 내부적으로 DTO → `Map<String, Object>` 변환
- XML에서는 컬럼명 기준으로 접근 가능

```java
mapper.insert(userDto);
```
---
### 4. @Column 기반 컬럼 매핑
- DTO/VO 필드명과 DB 컬럼명이 달라도 정확히 매핑
- NamingStrategy + `@Column(name = "...")` 조합 지원
```java
@Column(name = "member_name") // db 컬럼명
private String name;
```
---
### 5. NamingStrategy 확장 포인트
- 테이블명 / 컬럼명 생성 로직을 전략으로 분리
- 기본 전략: camelCase → snake_case
- 사용자 정의 전략 주입 가능
``` java
@Bean
public NamingStrategy namingStrategy() {
    return new CustomNamingStrategy();
}
```
---
### 6. EntityGenerator (개발 환경 전용)
> ⚠️ 운영 환경 사용 금지
- DB 스키마 변경 감지
  - 컬럼 추가 → 필드 자동 추가 + 주석
  - 컬럼 삭제 → [DELETED FROM DB] 주석
  - 타입 변경 → [Type Mismatch] 주석
  - 컬럼명 변경 추정 → [RENAMED?] 힌트 주석
-`application.yml`에서 명시적으로 활성화해야 동작
```yaml
mybatis-easy:
  generator:
    enabled: true

```
---

## 요구사항
* Java 17 이상
* Spring Boot 3.x
* MyBatis 3.x

## 설치 방법 (JitPack)
build.gradle 파일에 아래 설정을 추가하세요:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.MycroCosmo:mybatis-easy-starter:v0.0.2'
}

```

## 사용예시
엔티티 클래스에 어노테이션을 추가하고 BaseMapper를 상속받는 것만으로 동작합니다.
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
public interface UserMapper extends BaseMapper<UserVO, Long> {
}
```
## 설정 (Configuration)
MyBatis Easy Starter는 모든 기능이 기본적으로 보수적으로 동작하며,
일부 기능은 명시적으로 설정해야만 활성화됩니다.
---
### 1. 기본 CRUD SQL 자동 생성
```yaml
mybatis-easy:
  autosql:
    enabled: true
```
- 기본값: `true`
- mapper XML에 없는 CRUD SQL만 자동 생성
- 이미 정의된 SQL은 절대 덮어쓰지 않음
> 운영 환경에서도 안전하게 사용 가능
---
### 2. Entity / DB 동기화 Generator (개발 환경 전용)
```yaml
mybatis-easy:
  generator:
    enabled: true
    use-db-folder: true
```
| 옵션              | 기본값     | 설명                               |
| --------------- | ------- | -------------------------------- |
| `enabled`       | `false` | DB 스키마 기반 Entity/Mapper 생성 및 동기화 |
| `use-db-folder` | `true`  | mapper/{dbName}/ 구조 사용           |
- 개발 환경에서만 사용 권장
- 실행시 `src/main/java`,`src/main/resources`를  직접 수정
- 운영 환경에서는 반드시 false

---
### 3. Mapper XML 스캔 경로
기본적으로 아래 경로를 스캔합니다.
``` ruby
classpath*:mapper/**/*.xml
```
즉, 다음 구조가 모두 자동 포함됩니다:
```pgsql
mapper/
 ├─ user.xml
 ├─ postgresql/
 │   └─ user.xml
```
생성된 mapper(XML)을 추가로 포함하고 싶은 경우
```yaml
mybatis-easy:
  mapper:
    include-generated: true
```
- `mybatis-easy/mapper/**` 경로의 XML까지 함께 스캔
- Annotation Processor / DevTools와 연계 시 사용
---
### SQL 로그 출력 강제 설정 (선택)
```yaml
mybatis-easy:
  logging:
    force-stdout: true
```
- MyBatis 로그 구현체를 `StdOutImpl`로 강제
- 기존 프로젝트 로그 설정을 덮어쓰지 않음
- true로 설정한 경우에만 적용
> true로 설정한 경우에만 적용
> 사용자가 원할 때만 적용
---
### 5. NamingStrategy 커스터마이징
기본적략:
- 테이블명: `Class -> class_name
- 컬럼명: fieldName -> field_name
커스텀 전략 사용 시

```java
@Bean
public NamingStrategy namingStrategy() {
    return new NamingStrategy() {
        @Override
        public String tableName(Class<?> type) {
            return "tb_" + type.getSimpleName().toLowerCase();
        }

        @Override
        public String columnName(String fieldName) {
            return fieldName.toUpperCase();
        }
    };
}
```
- `@Column(name = "...")`이 있으면 항상 우선
- NamingStrategy는 fallback 용도
---
### 6.DTO 파라미터 자동 매핑 (설정 불필요)
- 별도 설정 없음
- Mapper 메서드 파라미터로 DTO 전달 가능
- 내부적으로 자동 Map 변환 처리
```java
userMapper.insert(userDto);
```
---
## ⚠️ 운영 환경 주의 사항
| 기능              | 운영 환경 |
| --------------- | ----- |
| CRUD 자동 생성      | ✅ 가능  |
| DTO 파라미터 매핑     | ✅ 가능  |
| Soft Delete 처리  | ✅ 가능  |
| EntityGenerator | ❌ 금지  |
| 코드/리소스 파일 수정    | ❌ 금지  |
