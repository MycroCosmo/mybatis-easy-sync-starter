package com.thenoah.dev.mybatis_easy_processor.scan;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.util.*;

public final class MapperMethodScanner {

    // XML 대상으로 제외할 MyBatis inline/provider 애노테이션들
    private static final Set<String> MYBATIS_ANNOTATION_QN = Set.of(
            "org.apache.ibatis.annotations.Select",
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete",
            "org.apache.ibatis.annotations.SelectProvider",
            "org.apache.ibatis.annotations.InsertProvider",
            "org.apache.ibatis.annotations.UpdateProvider",
            "org.apache.ibatis.annotations.DeleteProvider"
    );

    public record ScanResult(
            Map<String, Set<String>> expected,              // namespace -> method ids
            Map<String, Set<String>> overloadedMethodNames  // namespace -> duplicated names
    ) {}

    /**
     * @param mapperTypes @Mapper가 붙은 "인터페이스" TypeElement 집합
     */
    public ScanResult scan(Set<? extends TypeElement> mapperTypes) {
        if (mapperTypes == null || mapperTypes.isEmpty()) {
            return new ScanResult(Map.of(), Map.of());
        }

        // 재현성: namespace 정렬된 순서로 처리
        List<TypeElement> ordered = new ArrayList<>(mapperTypes);
        ordered.sort(Comparator.comparing(te -> te.getQualifiedName().toString()));

        Map<String, Set<String>> expected = new LinkedHashMap<>();
        Map<String, Set<String>> overloaded = new LinkedHashMap<>();

        for (TypeElement mapper : ordered) {
            String namespace = mapper.getQualifiedName().toString();

            Map<String, Integer> nameCount = new HashMap<>();
            Set<String> dupNames = new TreeSet<>(); // 재현성
            Set<String> ids = new TreeSet<>();      // 재현성

            for (ExecutableElement method : ElementFilter.methodsIn(mapper.getEnclosedElements())) {
                Set<Modifier> mods = method.getModifiers();
                if (mods.contains(Modifier.DEFAULT) || mods.contains(Modifier.STATIC)) continue;

                // Provider / @Select 등은 XML 대상에서 제외(정책 유지)
                if (hasMyBatisInlineSqlAnnotation(method)) continue;

                String name = method.getSimpleName().toString();

                int next = nameCount.getOrDefault(name, 0) + 1;
                nameCount.put(name, next);

                if (next == 2) {
                    dupNames.add(name); // 2번째 등장 순간 오버로딩 확정
                }

                ids.add(name);
            }

            if (!dupNames.isEmpty()) {
                overloaded.put(namespace, Collections.unmodifiableSet(dupNames));
            }
            expected.put(namespace, Collections.unmodifiableSet(ids));
        }

        return new ScanResult(
                Collections.unmodifiableMap(expected),
                Collections.unmodifiableMap(overloaded)
        );
    }

    private boolean hasMyBatisInlineSqlAnnotation(ExecutableElement method) {
        for (AnnotationMirror am : method.getAnnotationMirrors()) {
            // annotation type의 toString()은 보통 FQCN으로 나옴
            String qn = am.getAnnotationType().toString();
            if (MYBATIS_ANNOTATION_QN.contains(qn)) return true;
        }
        return false;
    }
}
