//package com.thenoah.dev.mybatis_easy_starter.processor;
//
//import javax.annotation.processing.*;
//import javax.lang.model.SourceVersion;
//import javax.lang.model.element.*;
//import javax.lang.model.type.DeclaredType;
//import javax.lang.model.type.TypeMirror;
//import javax.tools.Diagnostic;
//import javax.tools.FileObject;
//import javax.tools.StandardLocation;
//import java.io.*;
//import java.nio.charset.StandardCharsets;
//import java.util.*;
//import java.util.regex.Pattern;
//
//@SupportedAnnotationTypes("org.apache.ibatis.annotations.Mapper")
//@SupportedSourceVersion(SourceVersion.RELEASE_17)
//public class MybatisEasyProcessor extends AbstractProcessor {
//
//    private Filer filer;
//    private Messager messager;
//
//    private static final Pattern MAPPER_CLOSE_TAG = Pattern.compile("</mapper\\s*>", Pattern.CASE_INSENSITIVE);
//
//    @Override
//    public synchronized void init(ProcessingEnvironment processingEnv) {
//        super.init(processingEnv);
//        this.filer = processingEnv.getFiler();
//        this.messager = processingEnv.getMessager();
//    }
//
//    @Override
//    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
//        TypeElement mapperAnno = processingEnv.getElementUtils()
//                .getTypeElement("org.apache.ibatis.annotations.Mapper");
//        if (mapperAnno == null) return false;
//
//        Set<? extends Element> mappers = roundEnv.getElementsAnnotatedWith(mapperAnno);
//        for (Element e : mappers) {
//            if (e.getKind() == ElementKind.INTERFACE) {
//                processMapper((TypeElement) e);
//            }
//        }
//
//        // 이 프로세서는 @Mapper에 대해서만 처리했고, 다른 프로세서도 처리할 수 있게 false 권장
//        return false;
//    }
//
//    private void processMapper(TypeElement mapper) {
//        String simpleName = mapper.getSimpleName().toString();
//        String packageName = processingEnv.getElementUtils().getPackageOf(mapper).getQualifiedName().toString();
//
//        // 기본 namespace = 인터페이스 FQCN (가장 보편적)
//        String namespace = packageName + "." + simpleName;
//
//        // 생성할 파일 경로: build/classes/.../mybatis-easy/mapper/{SimpleName}.xml
//        String generatedPath = "mybatis-easy/mapper/" + simpleName + ".xml";
//
//        try {
//            // 기존 리소스가 프로젝트에 있으면 읽어오기 시도 (classpath에서)
//            // - src/main/resources/mapper/*** 는 컴파일 시 CLASS_OUTPUT로 복사되기 때문에 여기서 접근 가능
//            // - 위치를 강제하지 않고 여러 후보를 순회
//            String existingXml = readExistingMapperXml(simpleName);
//
//            String merged = mergeOrCreateXml(namespace, mapper, existingXml);
//
//            FileObject out = filer.createResource(StandardLocation.CLASS_OUTPUT, "", generatedPath, mapper);
//            try (Writer w = new OutputStreamWriter(out.openOutputStream(), StandardCharsets.UTF_8)) {
//                w.write(merged);
//            }
//
//            messager.printMessage(Diagnostic.Kind.NOTE,
//                    "MyBatis-Easy: generated mapper xml -> " + generatedPath + " (namespace=" + namespace + ")");
//
//        } catch (Exception ex) {
//            messager.printMessage(Diagnostic.Kind.WARNING,
//                    "MyBatis-Easy Processor error for " + namespace + ": " + ex.getMessage());
//        }
//    }
//
//    /**
//     * 프로젝트 리소스에서 기존 mapper xml을 찾아 읽는다.
//     * - 특정 DB 폴더(postgresql 등)를 강제하지 않음
//     */
//    private String readExistingMapperXml(String mapperSimpleName) {
//        String[] candidates = new String[] {
//                "mapper/" + mapperSimpleName + ".xml",
//                "mapper/postgresql/" + mapperSimpleName + ".xml",
//                "mapper/mysql/" + mapperSimpleName + ".xml",
//                "mapper/oracle/" + mapperSimpleName + ".xml",
//                "mybatis/mapper/" + mapperSimpleName + ".xml"
//        };
//
//        for (String path : candidates) {
//            try {
//                FileObject fo = filer.getResource(StandardLocation.CLASS_OUTPUT, "", path);
//                try (InputStream in = fo.openInputStream()) {
//                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
//                }
//            } catch (Exception ignore) {
//                // 후보를 계속 탐색
//            }
//        }
//        return null;
//    }
//
//    private String mergeOrCreateXml(String namespace, TypeElement mapper, String existingXml) {
//        String baseXml = (existingXml == null || existingXml.isBlank())
//                ? createEmptyMapperXml(namespace)
//                : existingXml;
//
//        // 기존 xml에 namespace가 없다면(또는 다르면) 강제로 바꾸진 않는다.
//        // (사용자가 다른 namespace 정책을 쓸 수 있음)
//        // 대신, existingXml이 없을 때만 namespace를 확정한다.
//
//        Set<String> existingIds = extractExistingStatementIds(baseXml);
//
//        StringBuilder newTags = new StringBuilder(512);
//        for (Element enclosed : mapper.getEnclosedElements()) {
//            if (enclosed.getKind() != ElementKind.METHOD) continue;
//
//            ExecutableElement method = (ExecutableElement) enclosed;
//            String methodName = method.getSimpleName().toString();
//
//            if (existingIds.contains(methodName)) continue;
//
//            String tag = determineTag(methodName);
//            if (tag != null) {
//                newTags.append(buildTag(tag, methodName, method));
//            }
//        }
//
//        if (newTags.length() == 0) {
//            return baseXml;
//        }
//
//        // </mapper> 직전에 삽입
//        int idx = lastIndexOfMapperClose(baseXml);
//        if (idx < 0) {
//            // 형식이 깨져 있으면 새로 만들고 태그 붙임
//            return createEmptyMapperXml(namespace, newTags.toString());
//        }
//
//        StringBuilder merged = new StringBuilder(baseXml.length() + newTags.length() + 16);
//        merged.append(baseXml, 0, idx);
//        if (!merged.toString().endsWith("\n")) merged.append("\n");
//        merged.append(newTags);
//        if (!newTags.toString().endsWith("\n")) merged.append("\n");
//        merged.append(baseXml.substring(idx));
//
//        return merged.toString();
//    }
//
//    private int lastIndexOfMapperClose(String xml) {
//        // 단순 lastIndexOf가 가장 빠르고 충분
//        return xml.lastIndexOf("</mapper>");
//    }
//
//    private Set<String> extractExistingStatementIds(String xml) {
//        // 간단 파싱: id="xxx" 추출
//        // (정교한 XML 파싱까지 갈 수도 있지만, 목적은 stub 중복 방지)
//        Set<String> ids = new HashSet<>();
//        int idx = 0;
//        while (true) {
//            idx = xml.indexOf("id=\"", idx);
//            if (idx < 0) break;
//            int start = idx + 4;
//            int end = xml.indexOf('"', start);
//            if (end < 0) break;
//            String id = xml.substring(start, end).trim();
//            if (!id.isEmpty()) ids.add(id);
//            idx = end + 1;
//        }
//        return ids;
//    }
//
//    private String buildTag(String tag, String methodName, ExecutableElement method) {
//        StringBuilder sb = new StringBuilder();
//        sb.append("\n    <").append(tag).append(" id=\"").append(methodName).append("\"");
//
//        if ("select".equals(tag)) {
//            String resultType = resolveResultType(method);
//            if (resultType != null && !resultType.isBlank()) {
//                sb.append(" resultType=\"").append(resultType).append("\"");
//            }
//        }
//
//        sb.append(">\n\n    </").append(tag).append(">\n");
//        return sb.toString();
//    }
//
//    /**
//     * resultType은 "클래스 simpleName"으로 낮추지 말고 FQCN을 기본으로 두는 게 안전함
//     * (alias 설정이 없으면 simpleName은 깨짐)
//     */
//    private String resolveResultType(ExecutableElement method) {
//        TypeMirror returnType = method.getReturnType();
//
//        // List<T>면 T 추출
//        if (returnType.toString().startsWith("java.util.List")) {
//            if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
//                returnType = dt.getTypeArguments().get(0);
//            }
//        }
//
//        Element typeElement = processingEnv.getTypeUtils().asElement(returnType);
//        if (typeElement instanceof TypeElement te) {
//            return te.getQualifiedName().toString();
//        }
//
//        // primitive/void 등은 resultType을 붙이지 않는 편이 낫다
//        return null;
//    }
//
//    private String determineTag(String name) {
//        String lower = name.toLowerCase();
//        if (startsWithAny(lower, "find", "select", "get")) return "select";
//        if (startsWithAny(lower, "insert", "save", "add")) return "insert";
//        if (startsWithAny(lower, "update", "modify")) return "update";
//        if (startsWithAny(lower, "delete", "remove")) return "delete";
//        return null;
//    }
//
//    private boolean startsWithAny(String str, String... prefixes) {
//        for (String p : prefixes) {
//            if (str.startsWith(p)) return true;
//        }
//        return false;
//    }
//
//    private String createEmptyMapperXml(String namespace) {
//        return createEmptyMapperXml(namespace, "");
//    }
//
//    private String createEmptyMapperXml(String namespace, String inner) {
//        StringBuilder sb = new StringBuilder(256 + inner.length());
//        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
//                .append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" ")
//                .append("\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n")
//                .append("<mapper namespace=\"").append(namespace).append("\">\n")
//                .append(inner == null ? "" : inner)
//                .append("\n</mapper>\n");
//        return sb.toString();
//    }
//}
