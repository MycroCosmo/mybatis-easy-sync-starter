package com.thenoah.dev.mybatis_easy_processor.validate;

import com.thenoah.dev.mybatis_easy_processor.model.DiffResult;
import com.thenoah.dev.mybatis_easy_processor.scan.XmlMapperScanner;

import java.util.*;

public class MapperXmlValidator {

	public static DiffResult diff(Map<String, Set<String>> expected, XmlMapperScanner.XmlIndex xmlIndex) {
	    Map<String, Set<String>> missing = new LinkedHashMap<>();
	    Map<String, Set<String>> orphan = new LinkedHashMap<>();

	    // missing: expected - actual(ids)
	    for (var e : expected.entrySet()) {
	        String ns = e.getKey();
	        Set<String> expIds = e.getValue();

	        Set<String> actIds = xmlIndex.idsOf(ns); // 없으면 empty
	        Set<String> miss = new LinkedHashSet<>(expIds);
	        miss.removeAll(actIds);

	        if (!miss.isEmpty()) {
	            missing.put(ns, miss);
	        }
	    }

	    // orphan: actual(ids) - expected
	    for (String ns : xmlIndex.namespaces()) {
	        Set<String> actIds = xmlIndex.idsOf(ns);
	        Set<String> expIds = expected.getOrDefault(ns, Set.of());

	        Set<String> orp = new LinkedHashSet<>(actIds);
	        orp.removeAll(expIds);

	        if (!orp.isEmpty()) {
	            orphan.put(ns, orp);
	        }
	    }
	    return new DiffResult(missing, orphan);
	}

}
