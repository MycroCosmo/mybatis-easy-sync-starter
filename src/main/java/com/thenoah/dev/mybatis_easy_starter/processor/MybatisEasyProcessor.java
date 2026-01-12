package com.thenoah.dev.mybatis_easy_starter.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@SupportedAnnotationTypes("*") 
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class MybatisEasyProcessor extends AbstractProcessor {

    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            // MyBatis 의존성 없이 문자열로 @Mapper 감지
            if (annotation.getQualifiedName().toString().equals("org.apache.ibatis.annotations.Mapper")) {
                for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    if (element.getKind() == ElementKind.INTERFACE) {
                        processMapper((TypeElement) element);
                    }
                }
            }
        }
        return true;
    }

    private void processMapper(TypeElement mapper) {
        String mapperName = mapper.getSimpleName().toString();
        // 리더님의 실제 프로젝트 구조에 맞춘 XML 상대 경로
        String xmlRelativePath = "mapper/postgresql/" + mapperName + ".xml";

        try {
            // 1. 컴파일된 결과물 위치(bin/main)에서 시작 경로 획득
            FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "");
            String uriPath = resource.toUri().getPath();
            
            if (uriPath.startsWith("/") && System.getProperty("os.name").toLowerCase().contains("win")) {
                uriPath = uriPath.substring(1);
            }

            // 2. 빌드 폴더를 소스 리소스 폴더로 치환
            String physicalPath = uriPath;
            if (uriPath.contains("/bin/main/")) {
                physicalPath = uriPath.replace("/bin/main/", "/src/main/resources/");
            } else if (uriPath.contains("/bin/")) {
                physicalPath = uriPath.replace("/bin/", "/src/main/resources/");
            }

            // 3. [핵심] 이클립스 환경에서 발생하는 'default/' 가상 경로 제거
            if (physicalPath.contains("/default/")) {
                physicalPath = physicalPath.replace("/default/", "/");
            }

            // 4. 리소스로더와 실제 XML 폴더 결합
            Path path = Paths.get(physicalPath, xmlRelativePath);
            
            // 5. 윈도우 역슬래시(\) 환경에서도 끼어있을 수 있는 'default' 최종 제거
            String finalPathStr = path.toString()
                                      .replace("\\default\\", "\\")
                                      .replace("/default/", "/");
            path = Paths.get(finalPathStr);

            messager.printMessage(Diagnostic.Kind.NOTE, "Final Target Path: " + path.toString());

            if (!Files.exists(path)) {
                messager.printMessage(Diagnostic.Kind.WARNING, "XML NOT FOUND: " + path.toString());
                return;
            }

            // 6. 파일 내용 읽기 및 분석
            List<String> lines = Files.readAllLines(path);
            String content = String.join("\n", lines);
            StringBuilder newTags = new StringBuilder();

            for (Element enclosed : mapper.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    ExecutableElement method = (ExecutableElement) enclosed;
                    String methodName = method.getSimpleName().toString();

                    // 이미 존재하면 건너뜀
                    if (content.contains("id=\"" + methodName + "\"")) continue;

                    String tag = determineTag(methodName);
                    if (tag != null) {
                        newTags.append(buildTag(tag, methodName, method));
                    }
                }
            }

            // 7. 변경사항이 있으면 XML 하단에 주입
            if (newTags.length() > 0) {
                injectTags(path, lines, newTags.toString());
                messager.printMessage(Diagnostic.Kind.NOTE, "Successfully injected into " + mapperName + ".xml");
            }
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Processor Error: " + e.getMessage());
        }
    }

    private String buildTag(String tag, String methodName, ExecutableElement method) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n    <").append(tag).append(" id=\"").append(methodName).append("\"");
        if ("select".equals(tag)) {
            String resultType = resolveResultType(method);
            if (resultType != null) sb.append(" resultType=\"").append(resultType).append("\"");
        }
        sb.append(">\n\n    </").append(tag).append(">\n");
        return sb.toString();
    }

    private String resolveResultType(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();
        if (returnType.toString().startsWith("java.util.List")) {
            DeclaredType declaredType = (DeclaredType) returnType;
            if (!declaredType.getTypeArguments().isEmpty()) returnType = declaredType.getTypeArguments().get(0);
        }
        Element typeElement = processingEnv.getTypeUtils().asElement(returnType);
        if (typeElement != null) return typeElement.getSimpleName().toString().toLowerCase();
        return null;
    }

    private void injectTags(Path path, List<String> lines, String newTags) throws IOException {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains("</mapper>")) {
                lines.add(i, newTags);
                break;
            }
        }
        Files.write(path, lines);
    }

    private String determineTag(String name) {
        String lower = name.toLowerCase();
        if (startsWithAny(lower, "find", "select", "get")) return "select";
        if (startsWithAny(lower, "insert", "save", "add")) return "insert";
        if (startsWithAny(lower, "update", "modify")) return "update";
        if (startsWithAny(lower, "delete", "remove")) return "delete";
        return null;
    }

    private boolean startsWithAny(String str, String... prefixes) {
        for (String prefix : prefixes) if (str.startsWith(prefix)) return true;
        return false;
    }
}