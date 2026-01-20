package com.thenoah.dev.mybatis_easy_processor.generate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SafeXmlWriter {
    private SafeXmlWriter() {}

    private static final String SECTION_BEGIN = "<!-- MES-AUTO-GENERATED:SECTION-BEGIN -->";
    private static final String SECTION_END   = "<!-- MES-AUTO-GENERATED:SECTION-END -->";

    // ====== precompiled patterns (핵심: 매 호출마다 Pattern.quote/compile 방지) ======
    private static final String QB = Pattern.quote(SECTION_BEGIN);
    private static final String QE = Pattern.quote(SECTION_END);

    // 섹션 내부 statement(4종)만 잡는다. id="..." 필수.
    private static final Pattern STMT_PATTERN = Pattern.compile(
            "<(select|insert|update|delete)\\b([^>]*?)\\bid\\s*=\\s*\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</\\1>",
            Pattern.CASE_INSENSITIVE);

    // marker indent 강제
    private static final Pattern P_BEGIN_LINE = Pattern.compile("(?m)^\\s*" + QB + "\\s*$");
    private static final Pattern P_END_LINE   = Pattern.compile("(?m)^\\s*" + QE + "\\s*$");

    // marker lines 분리
    private static final Pattern P_BEGIN_PREFIX = Pattern.compile("(?<!\\R)\\h*" + QB);
    private static final Pattern P_BEGIN_SUFFIX = Pattern.compile(QB + "\\h*(?!\\R)");
    private static final Pattern P_END_PREFIX   = Pattern.compile("(?<!\\R)\\h*" + QE);
    private static final Pattern P_END_SUFFIX   = Pattern.compile(QE + "\\h*(?!\\R)");
    private static final Pattern P_TOO_MANY_BLANKS = Pattern.compile("\\R{3,}");

    // 섹션 바디 spacing (여기는 큰 병목은 아니라 그대로 둠)
    private static final Pattern P_FORCE_STMT_TAB = Pattern.compile("(?m)^\\s*(<(?:select|insert|update|delete)\\b)");
    private static final Pattern P_FORCE_ORPHAN_TAB = Pattern.compile("(?m)^\\s*(<!--\\s*MES-ORPHAN:)");
    private static final Pattern P_SHRINK_GAPS = Pattern.compile(
            "(?s)(</(?:select|insert|update|delete)>)(\\s*\\R){2,}(\\t(?:<|<!--))"
    );

    // orphan 주석 lookback 범위
    private static final int ORPHAN_LOOKBACK = 400;

    // orphan ids가 너무 많아 regex가 지나치게 커질 때(극단 케이스) 안전 장치
    private static final int ORPHAN_REGEX_MAX_CHARS = 8000;

    public record SectionStatements(LinkedHashMap<String, String> byId) {}

    public static SectionStatements readMesSectionStatements(Path xmlPath) throws Exception {
        String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

        int[] r = findSectionRange(xml);
        if (r == null) return new SectionStatements(new LinkedHashMap<>());

        String body = xml.substring(r[2], r[3]);

        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        Matcher m = STMT_PATTERN.matcher(body);
        while (m.find()) {
            String full = m.group(0).trim();
            String id = m.group(3).trim();
            map.put(id, full);
        }
        return new SectionStatements(map);
    }

    /**
     * 섹션 범위: { beginIdx, endIdx, bodyStart, bodyEnd }
     *
     * body는 "BEGIN 라인 다음 줄"부터 "END 라인 시작 전"까지로 고정.
     * BEGIN/END가 태그와 같은 줄에 붙어있어도(망가진 파일) 여기서 복구된다.
     */
    private static int[] findSectionRange(String xml) {
        int b = xml.indexOf(SECTION_BEGIN);
        int e = xml.indexOf(SECTION_END);
        if (b < 0 || e < 0 || e <= b) return null;

        int afterBegin = b + SECTION_BEGIN.length();
        int bodyStart = skipToNextLineStart(xml, afterBegin);
        int bodyEnd = moveToLineStart(xml, e);

        if (bodyEnd < bodyStart) {
            bodyStart = afterBegin;
            bodyEnd = e;
        }

        return new int[] { b, e, bodyStart, bodyEnd };
    }

    private static int skipToNextLineStart(String s, int i) {
        if (i >= s.length()) return i;

        char c = s.charAt(i);
        if (c == '\n') return i + 1;
        if (c == '\r') {
            if (i + 1 < s.length() && s.charAt(i + 1) == '\n') return i + 2;
            return i + 1;
        }

        int p = i;
        while (p < s.length()) {
            char x = s.charAt(p);
            if (x == '\n') return p + 1;
            if (x == '\r') {
                if (p + 1 < s.length() && s.charAt(p + 1) == '\n') return p + 2;
                return p + 1;
            }
            p++;
        }
        return s.length();
    }

    private static int moveToLineStart(String s, int i) {
        int p = i;
        while (p > 0) {
            char c = s.charAt(p - 1);
            if (c == '\n' || c == '\r') break;
            p--;
        }
        return p;
    }

    /** 섹션이 없으면 '빈 섹션'만 </mapper> 직전에 삽입한다. (기존 statement는 건드리지 않음) */
    public static void ensureMesSectionExists(Path xmlPath) throws Exception {
        String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

        if (xml.contains(SECTION_BEGIN) && xml.contains(SECTION_END)) return;

        int mapperClose = xml.lastIndexOf("</mapper>");
        if (mapperClose < 0) {
            throw new IllegalStateException("Invalid mapper xml: missing </mapper>: " + xmlPath);
        }

        int pre = mapperClose;
        while (pre > 0 && Character.isWhitespace(xml.charAt(pre - 1))) pre--;

        String section = "\n\t" + SECTION_BEGIN + "\n" + "\t" + SECTION_END + "\n";
        String out = xml.substring(0, pre) + section + "\n" + xml.substring(mapperClose);

        out = normalizeAll(out); // 한 번에 정규화
        atomicWrite(xmlPath, out);
    }

    /**
     * missingIds만 섹션의 END 직전에 append한다.
     * - 섹션 내부에 이미 존재하면 append하지 않음
     * - 섹션 밖에 같은 id가 있어도 건드리지 않음
     * - 순서/기존 블록은 절대 변경하지 않음
     */
    public static void appendMissingStatements(Path xmlPath, Set<String> missingIds) throws Exception {
        if (missingIds == null || missingIds.isEmpty()) return;

        String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

        // 망가진 마커 복구 1회
        xml = normalizeSectionMarkerLines(xml);

        int[] r = findSectionRange(xml);
        if (r == null) return; // 호출 전에 ensureMesSectionExists 해야 함

        SectionStatements section = readMesSectionStatementsFromXml(xml, r);
        Set<String> existingInSection = section.byId().keySet();

        String body = xml.substring(r[2], r[3]);

        StringBuilder append = new StringBuilder();
        for (String id : missingIds) {
            if (existingInSection.contains(id)) continue;

            if (!body.isBlank() || append.length() > 0) append.append("\n\n");
            append.append(buildAutoGenBlockWithIndent(id));
        }

        if (append.length() == 0) return;

        String newBody = rstripAllWhitespace(body);
        if (!newBody.isBlank()) newBody = newBody + "\n\n" + append;
        else newBody = append.toString();

        String out = xml.substring(0, r[2]) + newBody + "\n" + xml.substring(r[3]);

        // 최종 정규화는 한 번만
        out = normalizeAll(out);
        atomicWrite(xmlPath, out);
    }

    /**
     * orphanIds는 '주석만' 삽입한다.
     * - 태그/내용/순서는 그대로 유지
     * - 동일 orphan 주석이 있으면 중복 삽입하지 않음
     *
     * orphanIds 개수만큼 Pattern.compile 반복하던 것을 "1회 compile + 1회 스캔"으로 변경
     */
    public static void markOrphansWithComment(Path xmlPath, Set<String> orphanIds) throws Exception {
        if (orphanIds == null || orphanIds.isEmpty()) return;

        String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

        // 망가진 마커 복구 1회
        xml = normalizeSectionMarkerLines(xml);

        int[] r = findSectionRange(xml);
        if (r == null) return;

        String body = xml.substring(r[2], r[3]);

        String updated = addOrphanCommentsInOnePass(body, orphanIds);
        if (updated.equals(body)) return;

        String out = xml.substring(0, r[2]) + updated + xml.substring(r[3]);

        // 최종 정규화는 한 번만
        out = normalizeAll(out);
        atomicWrite(xmlPath, out);
    }

    // === 내부 유틸 (XML 문자열에서 바로 파싱: 파일 재읽기 회피) ===
    private static SectionStatements readMesSectionStatementsFromXml(String xml, int[] r) {
        String body = xml.substring(r[2], r[3]);

        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        Matcher m = STMT_PATTERN.matcher(body);
        while (m.find()) {
            String full = m.group(0).trim();
            String id = m.group(3).trim();
            map.put(id, full);
        }
        return new SectionStatements(map);
    }

    /**
     * orphanIds를 OR로 묶어 Pattern을 1번만 컴파일하고 섹션 바디를 1번만 스캔하며 삽입.
     * - 기존 정책 유지: "해당 id의 statement 시작 태그 라인" 바로 위에 주석 삽입
     * - 이미 같은 주석이 있으면 스킵
     */
    private static String addOrphanCommentsInOnePass(String sectionBody, Set<String> orphanIds) {
        if (sectionBody == null || sectionBody.isEmpty()) return sectionBody;

        // 재현성: 입력이 Set이면 순서가 흔들릴 수 있으니 정렬
        List<String> ids = new ArrayList<>(orphanIds);
        ids.removeIf(s -> s == null || s.isBlank());
        if (ids.isEmpty()) return sectionBody;
        Collections.sort(ids);

        // 너무 많으면(정규식 비대화) 기존 방식(개별 처리)로 폴백
        String alternation = buildAlternation(ids);
        if (alternation.length() > ORPHAN_REGEX_MAX_CHARS) {
            String updated = sectionBody;
            for (String id : ids) {
                updated = addOrphanCommentIfNeededSlow(updated, id); // 기존 방식(느리지만 안전)
            }
            return updated;
        }

        // 그룹:
        // 1: indent
        // 2: start-tag
        // 3: tagName(select/insert/update/delete)
        // 4: id (매칭된 orphan id)
        Pattern p = Pattern.compile(
                "(?m)(^[\\t ]*)(<(select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"(" + alternation + ")\"[^>]*>)"
        );

        Matcher m = p.matcher(sectionBody);

        StringBuilder sb = new StringBuilder(sectionBody.length() + 128);
        int last = 0;

        // 동일 id가 여러 번 잡히는 비정상 파일 방어(중복 삽입 방지)
        Set<String> inserted = new HashSet<>();

        while (m.find()) {
            int insertPos = m.start(1);
            int tagEnd = m.end(2);
            String indent = m.group(1);
            String id = m.group(4);

            // 이미 같은 주석이 바로 위(근처)에 있으면 스킵
            int lookbackFrom = Math.max(0, insertPos - ORPHAN_LOOKBACK);
            String recent = sectionBody.substring(lookbackFrom, insertPos);
            if (recent.contains("<!-- MES-ORPHAN: id=" + id) || inserted.contains(id)) {
                sb.append(sectionBody, last, tagEnd);
                last = tagEnd;
                continue;
            }

            String comment = indent + "<!-- MES-ORPHAN: id=" + id + " no longer exists in mapper interface -->\n";

            sb.append(sectionBody, last, insertPos);
            sb.append(comment);
            sb.append(sectionBody, insertPos, tagEnd);

            inserted.add(id);
            last = tagEnd;
        }

        if (last == 0) {
            return sectionBody; // 아무 매칭도 없었으면 그대로
        }

        sb.append(sectionBody, last, sectionBody.length());
        return sb.toString();
    }

    private static String buildAlternation(List<String> ids) {
        // (?:a|b|c) 형태로 만들지 않아도 여기서는 캡처 그룹 안에서 쓰므로 "a|b|c"만 필요
        StringBuilder sb = new StringBuilder(ids.size() * 16);
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(ids.get(i)));
        }
        return sb.toString();
    }

    // 기존 방식(폴백용)
    private static String addOrphanCommentIfNeededSlow(String sectionBody, String id) {
        String idQuoted = Pattern.quote(id);

        Pattern p = Pattern.compile(
                "(?m)(^[\\t ]*)(<(select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"" + idQuoted + "\"[^>]*>)");
        Matcher m = p.matcher(sectionBody);
        if (!m.find()) return sectionBody;

        int lineStart = m.start(1);
        String indent = m.group(1);

        String comment = indent + "<!-- MES-ORPHAN: id=" + id + " no longer exists in mapper interface -->\n";

        int lookbackFrom = Math.max(0, lineStart - ORPHAN_LOOKBACK);
        String recent = sectionBody.substring(lookbackFrom, lineStart);
        if (recent.contains("<!-- MES-ORPHAN: id=" + id)) return sectionBody;

        return sectionBody.substring(0, lineStart) + comment + sectionBody.substring(lineStart);
    }

    private static String buildAutoGenBlockWithIndent(String id) {
        String tag = guessTagById(id);
        return "\t<" + tag + " id=\"" + id + "\">\n" +
               "\t  /* TODO: write SQL */\n" +
               "\t</" + tag + ">";
    }

    private static String guessTagById(String id) {
        String lower = id.toLowerCase(Locale.ROOT);

        if (lower.startsWith("insert") || lower.startsWith("save") || lower.startsWith("create")) return "insert";
        if (lower.startsWith("update") || lower.startsWith("modify")) return "update";
        if (lower.startsWith("delete") || lower.startsWith("remove")) return "delete";
        return "select";
    }

    /**
     * “최종 출력” 기준 정규화 파이프라인
     * - markerLines(망가짐 복구)
     * - markerIndent(라인 들여쓰기 강제)
     * - sectionBody(블록 사이 공백 정리)
     */
    private static String normalizeAll(String xml) {
        xml = normalizeSectionMarkerLines(xml);
        xml = normalizeSectionMarkerIndent(xml);
        xml = normalizeSectionBody(xml);
        return xml;
    }

    /** SECTION_BEGIN/END 라인은 항상 "\t<!-- ... -->" 형태로 강제 */
    private static String normalizeSectionMarkerIndent(String xml) {
        xml = P_BEGIN_LINE.matcher(xml).replaceAll("\t" + SECTION_BEGIN);
        xml = P_END_LINE.matcher(xml).replaceAll("\t" + SECTION_END);
        return xml;
    }

    /**
     * BEGIN/END "토큰"만 독립 라인으로 만든다.
     * "\\s*BEGIN\\s*"로 태그 앞 공백까지 먹는 방식은 금지(더 망가짐)
     */
    private static String normalizeSectionMarkerLines(String xml) {
        xml = P_BEGIN_PREFIX.matcher(xml).replaceAll("\n\t" + SECTION_BEGIN);
        xml = P_BEGIN_SUFFIX.matcher(xml).replaceAll(SECTION_BEGIN + "\n");

        xml = P_END_PREFIX.matcher(xml).replaceAll("\n\t" + SECTION_END);
        xml = P_END_SUFFIX.matcher(xml).replaceAll(SECTION_END + "\n");

        xml = P_TOO_MANY_BLANKS.matcher(xml).replaceAll("\n\n");
        return xml;
    }

    /** 섹션 바디만 꺼내서 "블록 사이 공백 1줄" + "첫 블록 탭 시작"을 강제 후 재삽입 */
    private static String normalizeSectionBody(String xml) {
        int[] r = findSectionRange(xml);
        if (r == null) return xml;

        String body = xml.substring(r[2], r[3]);
        String fixed = normalizeInterStatementSpacing(body);

        return xml.substring(0, r[2]) + fixed + xml.substring(r[3]);
    }

    private static String normalizeInterStatementSpacing(String sectionBody) {
        if (sectionBody == null || sectionBody.isBlank()) return sectionBody;

        sectionBody = P_FORCE_STMT_TAB.matcher(sectionBody).replaceAll("\t$1");
        sectionBody = P_FORCE_ORPHAN_TAB.matcher(sectionBody).replaceAll("\t$1");

        sectionBody = P_SHRINK_GAPS.matcher(sectionBody).replaceAll("$1\n\n$3");

        return rstripAllWhitespace(sectionBody);
    }

    /** 문자열 끝의 모든 whitespace 제거 (regex 대신 뒤에서 스캔) */
    private static String rstripAllWhitespace(String s) {
        if (s == null || s.isEmpty()) return s;

        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') end--;
            else break;
        }
        if (end == s.length()) return s;
        return s.substring(0, end);
    }

    public static void atomicWriteNew(Path target, String content) throws Exception {
        atomicWrite(target, content);
    }

    private static void atomicWrite(Path target, String content) throws Exception {
        Path dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);

        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
    }
}
