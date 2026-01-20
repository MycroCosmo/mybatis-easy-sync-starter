package com.thenoah.dev.mybatis_easy_processor.config;

import java.util.Locale;
import java.util.Map;

public record ProcessorOptions(
        String xmlDir,
        boolean failOnMissing,
        boolean failOnOrphan,
        boolean generateMissing,
        boolean debug
) {
    public static final String KEY_XML_DIR = "mes.xmlDir";
    public static final String KEY_FAIL_ON_MISSING = "mes.failOnMissing";
    public static final String KEY_FAIL_ON_ORPHAN = "mes.failOnOrphan";
    public static final String KEY_GENERATE_MISSING = "mes.generateMissing";
    public static final String KEY_DEBUG = "mes.debug";

    private static final String DEFAULT_XML_DIR = "src/main/resources/mapper";

    public static ProcessorOptions from(Map<String, String> opts) {
        String xmlDirRaw = opts.getOrDefault(KEY_XML_DIR, DEFAULT_XML_DIR);
        String xmlDir = normalizeXmlDir(xmlDirRaw);

        boolean failOnMissing = parseBooleanStrict(
                opts.get(KEY_FAIL_ON_MISSING),
                true,
                KEY_FAIL_ON_MISSING
        );

        boolean failOnOrphan = parseBooleanStrict(
                opts.get(KEY_FAIL_ON_ORPHAN),
                false,
                KEY_FAIL_ON_ORPHAN
        );

        boolean generateMissing = parseBooleanStrict(
                opts.get(KEY_GENERATE_MISSING),
                false,
                KEY_GENERATE_MISSING
        );

        boolean debug = parseBooleanStrict(
                opts.get(KEY_DEBUG),
                false,
                KEY_DEBUG
        );

        return new ProcessorOptions(xmlDir, failOnMissing, failOnOrphan, generateMissing, debug);
    }

    private static String normalizeXmlDir(String raw) {
        if (raw == null) return DEFAULT_XML_DIR;

        String v = raw.trim();
        if (v.isEmpty()) return DEFAULT_XML_DIR;

        // 끝 슬래시 제거: "mapper/" 같은 입력이 들어와도 일관성 유지
        while (v.endsWith("/") || v.endsWith("\\")) {
            v = v.substring(0, v.length() - 1).trim();
            if (v.isEmpty()) return DEFAULT_XML_DIR;
        }
        return v;
    }

    /**
     * boolean 옵션을 "엄격 파싱"
     * - null/빈값이면 defaultValue
     * - true/false(대소문자 무관)만 허용
     * - 그 외 값은 defaultValue로 떨어뜨리지 않고 예외 발생(오타 조기 발견)
     */
    private static boolean parseBooleanStrict(String raw, boolean defaultValue, String keyName) {
        if (raw == null) return defaultValue;

        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return defaultValue;

        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;

        throw new IllegalArgumentException(
                "Invalid boolean value for option '" + keyName + "': '" + raw + "'. " +
                "Use true or false."
        );
    }
}
