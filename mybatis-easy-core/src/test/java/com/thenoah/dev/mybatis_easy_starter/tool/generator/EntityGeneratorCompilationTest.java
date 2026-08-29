package com.thenoah.dev.mybatis_easy_starter.tool.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Map;
import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityGeneratorCompilationTest {

  @Test
  void generatedEntityCompilesWithoutLombok(@TempDir Path tempDir) throws Exception {
    DataSource dataSource = mockDataSource();
    Path javaRoot = tempDir.resolve("java");
    Path mapperRoot = tempDir.resolve("mapper");

    String originalUserDir = System.getProperty("user.dir");
    Files.createDirectory(tempDir.resolve(".git"));
    System.setProperty("user.dir", tempDir.toString());
    try {
      EntityGenerator generator = new EntityGenerator();
      generator.generate(
          dataSource,
          "example.generated",
          false,
          false,
          "",
          Map.of(),
          "misc",
          javaRoot,
          mapperRoot
      );
      generator.generate(
          dataSource,
          "example.generated",
          false,
          false,
          "",
          Map.of(),
          "misc",
          javaRoot,
          mapperRoot
      );
    } finally {
      System.setProperty("user.dir", originalUserDir);
    }

    Path source = javaRoot.resolve("example/generated/SampleUser.java");
    String generated = Files.readString(source);
    assertFalse(generated.contains("lombok"));
    assertTrue(generated.contains("setEmail(String email)"));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exitCode = compiler.run(
        null,
        null,
        errors,
        "-classpath",
        System.getProperty("java.class.path"),
        "-d",
        Files.createDirectory(tempDir.resolve("classes")).toString(),
        source.toString()
    );

    assertEquals(0, exitCode, errors.toString(StandardCharsets.UTF_8));
  }

  private DataSource mockDataSource() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    ResultSet initialTables = mock(ResultSet.class);
    ResultSet updatedTables = mock(ResultSet.class);
    ResultSet initialColumns = mock(ResultSet.class);
    ResultSet updatedColumns = mock(ResultSet.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(metadata.getTables(null, null, null, new String[]{"TABLE"}))
        .thenReturn(initialTables, updatedTables);
    when(initialTables.next()).thenReturn(true, false);
    when(updatedTables.next()).thenReturn(true, false);
    when(initialTables.getString("TABLE_NAME")).thenReturn("sample_user");
    when(updatedTables.getString("TABLE_NAME")).thenReturn("sample_user");
    when(metadata.getColumns(null, null, "sample_user", null))
        .thenReturn(initialColumns, updatedColumns);
    when(initialColumns.next()).thenReturn(true, true, false);
    when(initialColumns.getString("COLUMN_NAME")).thenReturn("id", "display_name");
    when(initialColumns.getInt("DATA_TYPE")).thenReturn(Types.BIGINT, Types.VARCHAR);
    when(updatedColumns.next()).thenReturn(true, true, true, false);
    when(updatedColumns.getString("COLUMN_NAME")).thenReturn("id", "display_name", "email");
    when(updatedColumns.getInt("DATA_TYPE")).thenReturn(Types.BIGINT, Types.VARCHAR, Types.VARCHAR);

    return dataSource;
  }
}
