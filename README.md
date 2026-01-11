# MyBatis Easy Sync Starter

A Spring Boot Starter for automated CRUD and seamless synchronization between Database schemas and Java Entities.

## Features
* **Auto CRUD Generation**: Automatically generates basic CRUD (Insert, Select, Update, Delete) at runtime without XML mapping.
* **Schema-Entity Synchronization**: Detects database column changes and automatically updates Java entity fields.
* **Type Safety Warning**: If a data type mismatch is detected between the DB and Entity, it adds a warning comment (e.g., `// [Type Warning]`) directly in the source code.
* **Code Preservation**: Ensures existing custom logic and code within the entity files are never overwritten or lost.

## Requirements
* Java 17 or higher
* Spring Boot 3.x
* MyBatis 3.x

## Installation (JitPack)
Add the repository to your `build.gradle`:
```gradle
repositories {
    maven { url '[https://jitpack.io](https://jitpack.io)' }
}

dependencies {
    implementation 'com.github.username:mybatis-easy-sync-starter:v1.0.0'
}
```

## Usage
Simply extend the base interface or use the provided annotations on your Entity classes.

```
@Table(name = "users")
public class User {
    @Id
    private Long id;
    
    // [Type Warning] DB type is BIGINT (Current: Integer)
    private Integer age;
}
```




# MyBatis Easy Sync Starter

데이터베이스 스키마와 Java 엔티티 간의 자동 CRUD 및 실시간 동기화를 지원하는 Spring Boot Starter입니다.

## 주요 기능
* **CRUD 자동 생성**: 별도의 XML 매핑 없이 실행 시점에 기본적인 CRUD(Insert, Select, Update, Delete) SQL을 자동으로 생성합니다.
* **스키마-엔티티 동기화**: DB 컬럼의 변경 사항을 감지하여 Java 엔티티 필드를 자동으로 추가하거나 업데이트합니다.
* **타입 불일치 경고**: DB와 엔티티 간의 데이터 타입이 맞지 않을 경우, 소스 코드에 직접 경고 주석(예: `// [Type Warning]`)을 추가합니다.
* **코드 보존**: 엔티티 파일 내에 작성된 기존 커스텀 로직이나 코드는 삭제되지 않고 안전하게 보존됩니다.

## 요구 사항
* Java 17 이상
* Spring Boot 3.x
* MyBatis 3.x

## 설치 방법 (JitPack)
`build.gradle` 파일에 아래 설정을 추가하세요:
```gradle
repositories {
    maven { url '[https://jitpack.io](https://jitpack.io)' }
}

dependencies {
    implementation 'com.github.username:mybatis-easy-sync-starter:v1.0.0'
}
```

## 사용 예시
엔티티 클래스에 어노테이션을 추가하는 것만으로 동작합니다.

```
@Table(name = "users")
public class User {
    @Id
    private Long id;
    
    // [Type Warning] DB type is BIGINT (Current: Integer)
    private Integer age;
}
```

