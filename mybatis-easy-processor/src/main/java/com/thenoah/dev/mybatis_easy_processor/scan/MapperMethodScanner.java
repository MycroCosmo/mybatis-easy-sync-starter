package com.thenoah.dev.mybatis_easy_processor.scan;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import java.util.*;

public class MapperMethodScanner {

    private static final Set<String> MYBATIS_ANNOTATION_PREFIXES = Set.of(
            "org.apache.ibatis.annotations.Select",
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete",
            "org.apache.ibatis.annotations.SelectProvider",
            "org.apache.ibatis.annotations.InsertProvider",
            "org.apache.ibatis.annotations.UpdateProvider",
            "org.apache.ibatis.annotations.DeleteProvider"
    );

    private final ProcessingEnvironment env;

    public MapperMethodScanner(ProcessingEnvironment env) {
        this.env = env;
    }

    public record ScanResult(
            Map<String, Set<String>> expected,              // namespace -> method ids
            Map<String, Set<String>> overloadedMethodNames  // namespace -> duplicated names
    ) {}

    public ScanResult scan(Set<? extends Element> mapperTypes) {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        Map<String, Set<String>> overloaded = new LinkedHashMap<>();

        for (Element e : mapperTypes) {
            if (e.getKind() != ElementKind.INTERFACE) continue;

            TypeElement mapper = (TypeElement) e;
            String namespace = mapper.getQualifiedName().toString();

            Map<String, Integer> nameCount = new HashMap<>();
            Set<String> ids = new LinkedHashSet<>();

            for (Element child : mapper.getEnclosedElements()) {
                if (child.getKind() != ElementKind.METHOD) continue;

                ExecutableElement method = (ExecutableElement) child;
                if (method.getModifiers().contains(Modifier.DEFAULT)) continue;
                if (method.getModifiers().contains(Modifier.STATIC)) continue;

                // Provider / @Select 등은 XML 대상에서 제외(정책 유지)
                if (hasMyBatisInlineSqlAnnotation(method)) continue;

                String name = method.getSimpleName().toString();
                nameCount.put(name, nameCount.getOrDefault(name, 0) + 1);
                ids.add(name);
            }

            // 오버로딩 감지
            Set<String> dup = new LinkedHashSet<>();
            for (var entry : nameCount.entrySet()) {
                if (entry.getValue() >= 2) dup.add(entry.getKey());
            }
            if (!dup.isEmpty()) {
                overloaded.put(namespace, dup);
            }

            expected.put(namespace, ids);
        }

        return new ScanResult(expected, overloaded);
    }

    private boolean hasMyBatisInlineSqlAnnotation(ExecutableElement method) {
        for (AnnotationMirror am : method.getAnnotationMirrors()) {
            String ann = am.getAnnotationType().toString();
            if (MYBATIS_ANNOTATION_PREFIXES.contains(ann)) return true;
        }
        return false;
    }
}
