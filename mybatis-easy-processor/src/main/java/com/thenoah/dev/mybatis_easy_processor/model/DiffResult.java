package com.thenoah.dev.mybatis_easy_processor.model;

import java.util.*;
import java.util.stream.Collectors;

public record DiffResult(Map<String, Set<String>> missing,
                         Map<String, Set<String>> orphan) {

    public DiffResult {
        // 방어 복사 + 내부 Set도 불변화
        missing = (missing == null)
                ? Map.of()
                : deepCopy(missing);

        orphan = (orphan == null)
                ? Map.of()
                : deepCopy(orphan);
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public String formatMissing(int limit) {
        return format("missing", missing, limit, false);
    }

    public String formatOrphan(int limit) {
        return format("orphan", orphan, limit, false);
    }

    /** debug 모드용 상세 출력 */
    public String formatMissingDetailed(int limit) {
        return format("missing", missing, limit, true);
    }

    public String formatOrphanDetailed(int limit) {
        return format("orphan", orphan, limit, true);
    }

    private static String format(String title,
                                 Map<String, Set<String>> map,
                                 int limit,
                                 boolean detailed) {

        if (map.isEmpty()) {
            return "MES " + title + ": <none>";
        }

        StringBuilder sb = new StringBuilder(256);

        int total = map.values().stream()
                .mapToInt(Set::size)
                .sum();

        if (!detailed) {
            sb.append("MES ").append(title)
              .append(": total=").append(total).append("\n");
        } else {
            sb.append("MES ").append(title).append(":\n");
        }

        List<String> namespaces = new ArrayList<>(map.keySet());
        Collections.sort(namespaces);

        for (String ns : namespaces) {
            Set<String> ids = map.getOrDefault(ns, Set.of());

            List<String> sortedIds = new ArrayList<>(ids);
            Collections.sort(sortedIds);

            if (!detailed) {
                // 요약형: 일부만 샘플 출력
                sb.append("- ").append(ns)
                  .append(" (").append(ids.size()).append("): ");

                String preview = sortedIds.stream()
                        .limit(limit)
                        .collect(Collectors.joining(", "));

                sb.append(preview);

                if (ids.size() > limit) {
                    sb.append(" ...");
                }
                sb.append("\n");

            } else {
                // 상세형: 전체 출력(단, limit 초과 시 잘림)
                sb.append("- ").append(ns)
                  .append(" (").append(ids.size()).append(")\n");

                int printed = 0;
                for (String id : sortedIds) {
                    sb.append("  * ").append(id).append("\n");
                    printed++;

                    if (printed >= limit) {
                        sb.append("  ... (truncated at ")
                          .append(limit)
                          .append(" entries)\n");
                        break;
                    }
                }
            }
        }

        return sb.toString();
    }
}
