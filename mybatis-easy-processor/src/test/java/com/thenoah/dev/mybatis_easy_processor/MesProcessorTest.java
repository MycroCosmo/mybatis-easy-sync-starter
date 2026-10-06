package com.thenoah.dev.mybatis_easy_processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MesProcessorTest {

    @TempDir
    Path xmlDir;

    private static final String NS = "demo.UserMapper";

    private static JavaFileObject mapper(String body) {
        return JavaFileObjects.forSourceString(NS,
                "package demo;\n"
                        + "import org.apache.ibatis.annotations.Mapper;\n"
                        + "@Mapper public interface UserMapper {\n" + body + "\n}");
    }

    private static JavaFileObject baseMapper(String extraBody) {
        return JavaFileObjects.forSourceString(NS,
                "package demo;\n"
                        + "import org.apache.ibatis.annotations.Mapper;\n"
                        + "import com.thenoah.dev.mybatis_easy_starter.core.mapper.BaseMapper;\n"
                        + "@Mapper public interface UserMapper extends BaseMapper<Object, Long> {\n" + extraBody + "\n}");
    }

    private void writeXml(String relativePath, String namespace, String... ids) throws IOException {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                .append("<mapper namespace=\"").append(namespace).append("\">\n");
        for (String id : ids) {
            sb.append("  <select id=\"").append(id).append("\" resultType=\"map\">SELECT 1</select>\n");
        }
        sb.append("</mapper>\n");
        Path target = xmlDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, sb.toString());
    }

    private Compilation compile(JavaFileObject source, String... extraOptions) {
        List<String> options = new ArrayList<>(List.of("-Ames.xmlDir=" + xmlDir.toAbsolutePath()));
        options.addAll(List.of(extraOptions));
        return Compiler.javac()
                .withProcessors(new MesProcessor())
                .withOptions(options)
                .compile(source);
    }

    private static List<String> messages(Compilation c, Diagnostic.Kind kind) {
        return c.diagnostics().stream()
                .filter(d -> d.getKind() == kind)
                .map(d -> d.getMessage(null))
                .toList();
    }

    @Test
    void succeedsWhenMapperAndXmlMatch() throws IOException {
        writeXml("UserMapper.xml", NS, "findByName");

        Compilation c = compile(mapper("Object findByName(String name);"));

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void failsOnMissingStatementByDefault() throws IOException {
        writeXml("UserMapper.xml", NS, "findByName");

        Compilation c = compile(mapper("Object findByName(String n);\nObject findByAge(int a);"));

        assertThat(c.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(String.join("\n", messages(c, Diagnostic.Kind.ERROR))).contains("findByAge");
    }

    @Test
    void missingIsOnlyAWarningWhenFailOnMissingIsFalse() throws IOException {
        writeXml("UserMapper.xml", NS);

        Compilation c = compile(mapper("Object findByAge(int a);"), "-Ames.failOnMissing=false");

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(String.join("\n", messages(c, Diagnostic.Kind.WARNING))).contains("findByAge");
    }

    @Test
    void orphanFailsOnlyWhenFailOnOrphanIsTrue() throws IOException {
        writeXml("UserMapper.xml", NS, "findByName", "legacy");
        JavaFileObject src = mapper("Object findByName(String n);");

        assertThat(compile(src).status()).isEqualTo(Compilation.Status.SUCCESS);

        Compilation strict = compile(src, "-Ames.failOnOrphan=true");
        assertThat(strict.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(String.join("\n", messages(strict, Diagnostic.Kind.ERROR))).contains("legacy");
    }

    @Test
    void overloadedMethodsAreRejected() throws IOException {
        writeXml("UserMapper.xml", NS, "find");

        Compilation c = compile(mapper("Object find(int a);\nObject find(String s);"));

        assertThat(c.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(String.join("\n", messages(c, Diagnostic.Kind.ERROR))).contains("Overloaded").contains("find");
    }

    @Test
    void baseMapperMethodsDoNotRequireXml() throws IOException {
        writeXml("UserMapper.xml", NS);

        Compilation c = compile(baseMapper(""), "-Ames.failOnOrphan=true");

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void baseMapperMethodOverriddenInXmlIsNotAnOrphan() throws IOException {
        writeXml("UserMapper.xml", NS, "findAll", "findById");

        Compilation c = compile(baseMapper(""), "-Ames.failOnOrphan=true");

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void customMethodOnBaseMapperStillRequiresXml() throws IOException {
        writeXml("UserMapper.xml", NS);

        Compilation c = compile(baseMapper("Object findByName(String n);"));

        assertThat(c.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(String.join("\n", messages(c, Diagnostic.Kind.ERROR))).contains("findByName");
    }

    @Test
    void xmlInSubdirectoriesIsRecognized() throws IOException {
        writeXml("user/UserMapper.xml", NS, "findByName");

        Compilation c = compile(mapper("Object findByName(String n);"));

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void inlineSqlAnnotationMethodsAreIgnored() throws IOException {
        writeXml("UserMapper.xml", NS);

        Compilation c = compile(mapper("@org.apache.ibatis.annotations.Select(\"SELECT 1\")\nint one();"));

        assertThat(c.status()).isEqualTo(Compilation.Status.SUCCESS);
    }
}
