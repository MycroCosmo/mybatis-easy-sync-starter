package com.thenoah.dev.mybatis_easy_processor.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record DiffResult(Map<String, Set<String>> missing,
                         Map<String, Set<String>> orphan) {

    public DiffResult {
        missing = (missing == null) ? Map.of() : new LinkedHashMap<>(missing);
        orphan  = (orphan  == null) ? Map.of() : new LinkedHashMap<>(orphan);
    }

    public String formatMissing(int limit) {
        return "MES missing namespaces=" + missing.keySet();
    }

    public String formatOrphan(int limit) {
        return "MES orphan namespaces=" + orphan.keySet();
    }
}
