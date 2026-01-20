package com.thenoah.dev.mybatis_easy_processor.config;

import java.util.Map;

public record ProcessorOptions(
        String xmlDir,
        boolean failOnMissing,
        boolean failOnOrphan,
        boolean generateMissing
) {
    public static final String KEY_XML_DIR = "mes.xmlDir";
    public static final String KEY_FAIL_ON_MISSING = "mes.failOnMissing";
    public static final String KEY_FAIL_ON_ORPHAN = "mes.failOnOrphan";
    public static final String KEY_GENERATE_MISSING = "mes.generateMissing";

    public static ProcessorOptions from(Map<String, String> opts) {
        String xmlDir = opts.getOrDefault(KEY_XML_DIR, "src/main/resources/mapper");
        boolean failOnMissing = Boolean.parseBoolean(opts.getOrDefault(KEY_FAIL_ON_MISSING, "true"));
        boolean failOnOrphan = Boolean.parseBoolean(opts.getOrDefault(KEY_FAIL_ON_ORPHAN, "false"));
        boolean generateMissing = Boolean.parseBoolean(opts.getOrDefault(KEY_GENERATE_MISSING, "false"));
        return new ProcessorOptions(xmlDir, failOnMissing, failOnOrphan, generateMissing);
    }
}
