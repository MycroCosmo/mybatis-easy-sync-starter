package com.thenoah.dev.mybatis_easy_processor.generate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SafeXmlWriter {
	private SafeXmlWriter() {
	}

	private static final String SECTION_BEGIN = "<!-- MES-AUTO-GENERATED:SECTION-BEGIN -->";
	private static final String SECTION_END = "<!-- MES-AUTO-GENERATED:SECTION-END -->";

	// 섹션 내부에서 statement(4종)만 잡는다. id="..." 필수.
	private static final Pattern STMT_PATTERN = Pattern.compile(
			"<(select|insert|update|delete)\\b([^>]*?)\\bid\\s*=\\s*\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</\\1>",
			Pattern.CASE_INSENSITIVE);

	public record SectionStatements(LinkedHashMap<String, String> byId) {
	}

	public static SectionStatements readMesSectionStatements(Path xmlPath) throws Exception {
		String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

		int[] r = findSectionRange(xml);
		if (r == null)
			return new SectionStatements(new LinkedHashMap<>());

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
	 * ✅ 핵심: body는 "BEGIN 라인 다음 줄"부터 "END 라인 시작 전"까지로 고정. BEGIN/END가 태그와 같은 줄에
	 * 붙어있어도(망가진 파일) 여기서 복구된다.
	 */
	private static int[] findSectionRange(String xml) {
		int b = xml.indexOf(SECTION_BEGIN);
		int e = xml.indexOf(SECTION_END);
		if (b < 0 || e < 0 || e <= b)
			return null;

		// BEGIN 라인 다음 줄부터 body 시작
		int afterBegin = b + SECTION_BEGIN.length();
		int bodyStart = skipToNextLineStart(xml, afterBegin);

		// END 라인 시작(개행 직후 위치)으로 body 끝
		int bodyEnd = moveToLineStart(xml, e);

		// 안전: 역전되면 최소한 기존 방식으로 폴백
		if (bodyEnd < bodyStart) {
			bodyStart = afterBegin;
			bodyEnd = e;
		}

		return new int[] { b, e, bodyStart, bodyEnd };
	}

	private static int skipToNextLineStart(String s, int i) {
		if (i >= s.length())
			return i;

		// 이미 바로 개행이면 다음 줄 시작으로
		char c = s.charAt(i);
		if (c == '\n')
			return i + 1;
		if (c == '\r') {
			if (i + 1 < s.length() && s.charAt(i + 1) == '\n')
				return i + 2;
			return i + 1;
		}

		// BEGIN 뒤에 바로 태그가 붙어버린 "망가진" 케이스:
		// 여기서는 "현재 줄 끝"까지 스킵해서 다음 줄을 bodyStart로 잡는다.
		int p = i;
		while (p < s.length()) {
			char x = s.charAt(p);
			if (x == '\n')
				return p + 1;
			if (x == '\r') {
				if (p + 1 < s.length() && s.charAt(p + 1) == '\n')
					return p + 2;
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
			if (c == '\n' || c == '\r')
				break;
			p--;
		}
		return p;
	}

	/** 섹션이 없으면 '빈 섹션'만 </mapper> 직전에 삽입한다. (기존 statement는 건드리지 않음) */
	public static void ensureMesSectionExists(Path xmlPath) throws Exception {
		String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

		if (xml.contains(SECTION_BEGIN) && xml.contains(SECTION_END))
			return;

		int mapperClose = xml.lastIndexOf("</mapper>");
		if (mapperClose < 0)
			throw new IllegalStateException("Invalid mapper xml: missing </mapper>: " + xmlPath);

		// </mapper> 직전 공백 제거
		int pre = mapperClose;
		while (pre > 0 && Character.isWhitespace(xml.charAt(pre - 1)))
			pre--;

		String section = "\n\t" + SECTION_BEGIN + "\n" + "\t" + SECTION_END + "\n";

		String out = xml.substring(0, pre) + section + "\n" + xml.substring(mapperClose);

		out = normalizeSectionMarkerLines(out);
		out = normalizeSectionMarkerIndent(out);
		out = normalizeSectionBody(out);

		atomicWrite(xmlPath, out);
	}

	/**
	 * missingIds만 섹션의 END 직전에 append한다. - 섹션 내부에 이미 존재하면 append하지 않음 - 섹션 밖에 같은 id가
	 * 있어도 건드리지 않음 - 순서/기존 블록은 절대 변경하지 않음
	 */
	public static void appendMissingStatements(Path xmlPath, Set<String> missingIds) throws Exception {
		if (missingIds == null || missingIds.isEmpty())
			return;

		String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

		// ✅ 먼저 마커 줄 정규화(망가진 파일 복구)
		xml = normalizeSectionMarkerLines(xml);

		int[] r = findSectionRange(xml);
		if (r == null)
			return; // 호출 전에 ensureMesSectionExists 해야 함

		// 섹션 내부 id만 파악
		SectionStatements section = readMesSectionStatementsFromXml(xml, r);
		Set<String> existingInSection = section.byId().keySet();

		String body = xml.substring(r[2], r[3]);

		StringBuilder append = new StringBuilder();
		for (String id : missingIds) {
			if (existingInSection.contains(id))
				continue;

			if (!body.isBlank() || append.length() > 0)
				append.append("\n\n");
			append.append(buildAutoGenBlockWithIndent(id));
		}

		if (append.length() == 0)
			return;

		// body 끝 공백 정리(늘어나는 공백 방지)
		String newBody = rstripAllWhitespace(body);
		if (!newBody.isBlank())
			newBody = newBody + "\n\n" + append;
		else
			newBody = append.toString(); // 첫 append인 경우 (앞에 불필요한 개행 넣지 않음)

		String out = xml.substring(0, r[2]) + newBody + "\n" + xml.substring(r[3]);

		out = normalizeSectionMarkerLines(out);   
		out = normalizeSectionMarkerIndent(out);
		out = normalizeSectionBody(out);        
		out = normalizeSectionMarkerLines(out);  
		out = normalizeSectionMarkerIndent(out);

		atomicWrite(xmlPath, out);
	}

	/**
	 * orphanIds는 '주석만' 삽입한다. - 태그/내용/순서는 그대로 유지 - 동일 orphan 주석이 있으면 중복 삽입하지 않음
	 */
	public static void markOrphansWithComment(Path xmlPath, Set<String> orphanIds) throws Exception {
		if (orphanIds == null || orphanIds.isEmpty())
			return;

		String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);

		// ✅ 먼저 마커 줄 정규화(망가진 파일 복구)
		xml = normalizeSectionMarkerLines(xml);

		int[] r = findSectionRange(xml);
		if (r == null)
			return;

		String body = xml.substring(r[2], r[3]);

		String updated = body;
		for (String id : orphanIds) {
			updated = addOrphanCommentIfNeeded(updated, id);
		}

		if (updated.equals(body))
			return;

		String out = xml.substring(0, r[2]) + updated + xml.substring(r[3]);

		out = normalizeSectionMarkerLines(out);
		out = normalizeSectionBody(out);
		out = normalizeSectionMarkerIndent(out);

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

	private static String addOrphanCommentIfNeeded(String sectionBody, String id) {
		String idQuoted = Pattern.quote(id);

		// 섹션 내부에서 해당 id의 statement "시작 태그" 라인 찾기 (라인 시작의 들여쓰기 유지)
		Pattern p = Pattern.compile(
				"(?m)(^[\\t ]*)(<(select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"" + idQuoted + "\"[^>]*>)");
		Matcher m = p.matcher(sectionBody);
		if (!m.find())
			return sectionBody;

		int lineStart = m.start(1);
		String indent = m.group(1);

		String comment = indent + "<!-- MES-ORPHAN: id=" + id + " no longer exists in mapper interface -->\n";

		// 바로 위에 이미 같은 orphan 주석이 있으면 스킵
		int lookbackFrom = Math.max(0, lineStart - 400);
		String recent = sectionBody.substring(lookbackFrom, lineStart);
		if (recent.contains("<!-- MES-ORPHAN: id=" + id))
			return sectionBody;

		return sectionBody.substring(0, lineStart) + comment + sectionBody.substring(lineStart);
	}

	private static String buildAutoGenBlockWithIndent(String id) {
		String tag = guessTagById(id);

		return "" + "\t<" + tag + " id=\"" + id + "\">\n" + "\t  /* TODO: write SQL */\n" + "\t</" + tag + ">";
	}

	private static String guessTagById(String id) {
		String lower = id.toLowerCase(Locale.ROOT);

		if (lower.startsWith("insert") || lower.startsWith("save") || lower.startsWith("create"))
			return "insert";
		if (lower.startsWith("update") || lower.startsWith("modify"))
			return "update";
		if (lower.startsWith("delete") || lower.startsWith("remove"))
			return "delete";
		return "select";
	}

	/** SECTION_BEGIN/END 라인은 항상 "\t<!-- ... -->" 형태로 강제 */
	private static String normalizeSectionMarkerIndent(String xml) {
		xml = xml.replaceAll("(?m)^\\s*" + Pattern.quote(SECTION_BEGIN) + "\\s*$", "\t" + SECTION_BEGIN);
		xml = xml.replaceAll("(?m)^\\s*" + Pattern.quote(SECTION_END) + "\\s*$", "\t" + SECTION_END);
		return xml;
	}

	/**
	 * ✅ 안전 버전: BEGIN/END "토큰"만 독립 라인으로 만든다. 기존처럼 "\\s*BEGIN\\s*"로 태그 앞 공백까지 먹으면 더
	 * 망가질 수 있어서 금지.
	 */
	private static String normalizeSectionMarkerLines(String xml) {
		// BEGIN 앞에 개행이 없으면 추가 (같은 줄에 붙어 있어도 분리)
		xml = xml.replaceAll("(?<!\\R)\\h*" + Pattern.quote(SECTION_BEGIN), "\n\t" + SECTION_BEGIN);
		// BEGIN 뒤에 개행이 없으면 추가
		xml = xml.replaceAll(Pattern.quote(SECTION_BEGIN) + "\\h*(?!\\R)", SECTION_BEGIN + "\n");

		// END 앞에 개행이 없으면 추가 (</select> 뒤에 붙는 케이스 해결)
		xml = xml.replaceAll("(?<!\\R)\\h*" + Pattern.quote(SECTION_END), "\n\t" + SECTION_END);
		// END 뒤에 개행이 없으면 추가
		xml = xml.replaceAll(Pattern.quote(SECTION_END) + "\\h*(?!\\R)", SECTION_END + "\n");

		// 빈 줄 과다 정리 (선택)
		xml = xml.replaceAll("\\R{3,}", "\n\n");
		return xml;
	}

	/** 섹션 바디만 꺼내서 "블록 사이 공백 1줄" + "첫 블록 탭 시작"을 강제 후 재삽입 */
	private static String normalizeSectionBody(String xml) {
		int[] r = findSectionRange(xml);
		if (r == null)
			return xml;

		String body = xml.substring(r[2], r[3]);
		String fixed = normalizeInterStatementSpacing(body);

		return xml.substring(0, r[2]) + fixed + xml.substring(r[3]);
	}

	/**
	 * statement 블록 “사이”의 빈 줄을 1줄(=개행 2번)로 고정 (SQL 내부는 건드리지 않음) - </tag>와 다음 블록
	 * 시작(\t< or \t<!--) 사이의 과도한 공백을 축소 - 블록/주석 라인의 선행 공백은 \t로 강제(방어)
	 */
	private static String normalizeInterStatementSpacing(String sectionBody) {
		if (sectionBody == null || sectionBody.isBlank())
			return sectionBody;

		// 블록/주석 라인은 \t로 시작 강제
		sectionBody = sectionBody.replaceAll("(?m)^\\s*(<(?:select|insert|update|delete)\\b)", "\t$1");
		sectionBody = sectionBody.replaceAll("(?m)^\\s*(<!--\\s*MES-ORPHAN:)", "\t$1");

		// </tag> 다음에 공백 줄이 2개 이상이면 1개로 축소 (다음이 주석/태그일 때만)
		sectionBody = sectionBody.replaceAll("(?s)(</(?:select|insert|update|delete)>)(\\s*\\R){2,}(\\t(?:<|<!--))",
				"$1\n\n$3");

		// 끝 공백 제거
		return rstripAllWhitespace(sectionBody);
	}

	/** 문자열 끝의 모든 whitespace(\s, 탭 포함) 제거 */
	private static String rstripAllWhitespace(String s) {
		if (s == null || s.isEmpty())
			return s;
		return s.replaceAll("[\\s\\t]+\\z", "");
	}

	public static void atomicWriteNew(Path target, String content) throws Exception {
		atomicWrite(target, content);
	}

	private static void atomicWrite(Path target, String content) throws Exception {
		Path dir = target.getParent();
		if (dir != null)
			Files.createDirectories(dir);

		Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
		try {
			Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);

			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (Exception ignore) {
			}
		}
	}
}
