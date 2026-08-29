package com.thenoah.dev.mybatis_easy_starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumerCompilationTest {

  @Test
  void coreClasspathDoesNotBreakConsumerCompilation(@TempDir Path tempDir) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "A JDK is required to run the consumer compilation test");

    Path source = tempDir.resolve("Consumer.java");
    Path classes = Files.createDirectory(tempDir.resolve("classes"));
    Files.writeString(source, "final class Consumer {}", StandardCharsets.UTF_8);

    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exitCode = compiler.run(
        null,
        null,
        errors,
        "-classpath",
        System.getProperty("java.class.path"),
        "-d",
        classes.toString(),
        source.toString()
    );

    assertEquals(0, exitCode, errors.toString(StandardCharsets.UTF_8));
  }
}
