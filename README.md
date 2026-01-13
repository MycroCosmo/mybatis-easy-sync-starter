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
어노테이션 기반의 지능형 CRUD 자동 생성 및 DTO-VO 간 유연한 매핑을 지원하는 Spring Boot Starter입니다.

## 주요기능
* CRUD 자동 생성: 별도의 XML 매핑 없이 실행 시점에 기본적인 CRUD(Insert, Select, Update, Delete) SQL을 자동으로 생성합니다.
* 지능형 삭제 정책: @SoftDelete 어노테이션 유무를 감지하여 물리 삭제(DELETE)와 논리 삭제(UPDATE)를 자동으로 전환합니다.
* DTO 호환 매핑: 필드명이 달라도 @Column 어노테이션을 분석하여 DTO 파라미터를 DB 컬럼에 정확히 매핑합니다.
* 자동 조회 필터링: findAll() 호출 시 소프트 삭제된 데이터를 조회 조건에서 자동으로 제외합니다.

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
    implementation 'com.github.MycroCosmo:mybatis-easy-sync-starter:v0.0.1'
}


```

## 사용예시
엔티티 클래스에 어노테이션을 추가하고 BaseMapper를 상속받는 것만으로 동작합니다.
```java
@Table(name = "users")
public class UserVO {
    @Id
    @Column(name = "user_id") // DB 컬럼명이 달라도 자동 매핑
    private Long id;
    
    // @SoftDelete 설정 시 deleteById() 호출 시 자동으로 UPDATE 실행
    @SoftDelete
    private LocalDateTime deletedAt;
}
```
