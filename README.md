# MyBatis Easy Sync Starter

A Java 17 library for reducing repetitive MyBatis work while preserving direct control over SQL.

The project is intentionally not an ORM replacement. Its goal is narrower: automate predictable CRUD and consistency checks, but keep hand-written Mapper XML as the source of truth when custom SQL is needed.

## Problem

In MyBatis projects, the same maintenance work tends to repeat:

- basic CRUD statements are rewritten for every table;
- Java fields and database columns can drift apart;
- Mapper interfaces and XML statement IDs can become inconsistent;
- code generation can become dangerous if it overwrites hand-written SQL.

This project separates those concerns into runtime support and compile-time validation.

## Modules

### `mybatis-easy-core`

Runtime support for common MyBatis operations.

- `BaseMapper<T, ID>` provides insert, findById, findAll, page, count, update, and delete operations.
- User-defined XML statements take precedence over generated CRUD.
- `@Table`, `@Id`, `@Column`, and `@SoftDelete` describe mapping intent.
- A configurable `NamingStrategy` supports conventions such as camelCase to snake_case.
- The development-time entity generator inspects JDBC metadata and provides schema-change hints.

### `mybatis-easy-processor`

A Java annotation processor that compares `@Mapper` interfaces with Mapper XML.

It detects:

- Mapper methods that have no matching XML statement;
- XML statements that no longer have a Mapper method;
- method overloading that would collide on the same XML statement ID.

Optional stub generation is restricted to a managed section so existing SQL is not rewritten wholesale.

## Design decisions

### 1. Custom SQL wins

Generated CRUD is only a default. If the same statement ID already exists in user XML, the library leaves it alone.

This keeps optimization queries and domain-specific SQL explicit.

### 2. Destructive changes are not applied automatically

Adding a field is comparatively safe. Removing or renaming one is not.

The generator therefore treats potentially destructive schema differences as developer-review items instead of silently editing application code.

### 3. Runtime generation and compile-time validation are separate

The project deliberately separates:

- runtime CRUD support;
- development-time entity/schema assistance;
- compile-time Mapper/XML validation.

This keeps each mechanism easier to disable, test, and reason about.

## Example

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
    // Common CRUD is available without duplicating XML.
    // Add XML only when custom SQL is needed.
}
```

## BaseMapper API

```java
int insert(Object entity);
Optional<T> findById(ID id);
List<T> findAll();
List<T> findPage(long offset, int limit);
long countAll();
int update(Object entity);
int deleteById(ID id);
```

## Processor policy

The annotation processor is conservative by default.

- Missing statements can fail the build.
- Orphan statements can be reported separately.
- Optional generated stubs are written only inside a dedicated managed section.
- Existing statements are not deleted automatically.

Typical compiler options:

```text
-Ames.xmlDir=src/main/resources/mapper
-Ames.failOnMissing=true
-Ames.failOnOrphan=false
-Ames.generateMissing=false
-Ames.debug=false
```

## Project structure

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

## Build

Requirements:

- Java 17+
- Gradle

```bash
./gradlew clean test
```

## Scope

This project is intended for developers who want to keep MyBatis and hand-written SQL, but remove some of the repetitive and error-prone maintenance around it.

It does not attempt to provide JPA-style persistence context, entity lifecycle management, or a query DSL.
