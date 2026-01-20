package com.thenoah.dev.mybatis_easy_processor.validate;

import com.thenoah.dev.mybatis_easy_processor.model.DiffResult;
import com.thenoah.dev.mybatis_easy_processor.scan.XmlMapperScanner;

import java.util.*;

public final class MapperXmlValidator {

    private MapperXmlValidator() {}

    public static DiffResult diff(Map<String, Set<String>> expected,
                                  XmlMapperScanner.XmlIndex xmlIndex) {

        if (expected == null) expected = Map.of();

        Map<String, Set<String>> missing = new LinkedHashMap<>();
        Map<String, Set<String>> orphan  = new LinkedHashMap<>();

        // 재현성: namespace 정렬
        List<String> expectedNamespaces = new ArrayList<>(expected.keySet());
        Collections.sort(expectedNamespaces);

        // missing: expected - actual(ids)
        for (String ns : expectedNamespaces) {
            Set<String> expIds = expected.getOrDefault(ns, Set.of());
            if (expIds.isEmpty()) continue;

            Set<String> actIds = xmlIndex.idsOf(ns); // 없으면 empty
            if (actIds.isEmpty()) {
                // 전부 missing
                missing.put(ns, unmodifiableSortedSet(expIds));
                continue;
            }

            Set<String> miss = new LinkedHashSet<>(expIds);
            miss.removeAll(actIds);

            if (!miss.isEmpty()) {
                missing.put(ns, unmodifiableSortedSet(miss));
            }
        }

        // orphan: actual(ids) - expected
        List<String> actualNamespaces = new ArrayList<>(xmlIndex.namespaces());
        Collections.sort(actualNamespaces);

        for (String ns : actualNamespaces) {
            Set<String> actIds = xmlIndex.idsOf(ns);
            if (actIds.isEmpty()) continue;

            Set<String> expIds = expected.getOrDefault(ns, Set.of());
            if (expIds.isEmpty()) {
                // 전부 orphan
                orphan.put(ns, unmodifiableSortedSet(actIds));
                continue;
            }

            Set<String> orp = new LinkedHashSet<>(actIds);
            orp.removeAll(expIds);

            if (!orp.isEmpty()) {
                orphan.put(ns, unmodifiableSortedSet(orp));
            }
        }

        // Map도 불변으로 감싸서 리턴(안전)
        return new DiffResult(
                Collections.unmodifiableMap(missing),
                Collections.unmodifiableMap(orphan)
        );
    }

    /**
     * 결과의 재현성/가독성:
     * - Set 내용을 정렬(TreeSet)로 고정
     * - 불변으로 감싸서 외부 수정 방지
     */
    private static Set<String> unmodifiableSortedSet(Set<String> src) {
        if (src == null || src.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new TreeSet<>(src));
    }
}
