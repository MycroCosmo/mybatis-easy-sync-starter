package com.thenoah.dev.mybatis_easy_processor;

import com.google.auto.service.AutoService;
import com.thenoah.dev.mybatis_easy_processor.config.ProcessorOptions;
import com.thenoah.dev.mybatis_easy_processor.generate.XmlStubGenerator;
import com.thenoah.dev.mybatis_easy_processor.model.DiffResult;
import com.thenoah.dev.mybatis_easy_processor.scan.MapperMethodScanner;
import com.thenoah.dev.mybatis_easy_processor.scan.XmlMapperScanner;
import com.thenoah.dev.mybatis_easy_processor.validate.MapperXmlValidator;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.LinkedHashSet;
import java.util.Set;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions({
        ProcessorOptions.KEY_XML_DIR,
        ProcessorOptions.KEY_FAIL_ON_MISSING,
        ProcessorOptions.KEY_FAIL_ON_ORPHAN,
        ProcessorOptions.KEY_GENERATE_MISSING
})
public class MesProcessor extends AbstractProcessor {

    // 라운드별 누적 (핵심)
    private final Set<Element> collectedMappers = new LinkedHashSet<>();
    private TypeElement mapperAnn;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("org.apache.ibatis.annotations.Mapper");
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        mapperAnn = processingEnv.getElementUtils().getTypeElement("org.apache.ibatis.annotations.Mapper");

        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "MES init options=" + processingEnv.getOptions());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mapperAnn == null) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "mybatis @Mapper annotation not found on classpath."
            );
            return false;
        }

        // ✅ 1) 먼저 수집부터
        var newly = roundEnv.getElementsAnnotatedWith(mapperAnn);
        collectedMappers.addAll(newly);

        // ✅ 2) 라운드 로그 (수집 후 기준으로 찍기)
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                "MES round: processingOver=" + roundEnv.processingOver()
                        + ", annotations=" + annotations.size()
                        + ", newlyFoundMappers=" + newly.size()
                        + ", collected=" + collectedMappers.size()
        );

        // ✅ 3) 마지막 라운드에서만 실행
        if (!roundEnv.processingOver()) return false;

        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "MES last round start");

        ProcessorOptions options = ProcessorOptions.from(processingEnv.getOptions());

        try {
            if (collectedMappers.isEmpty()) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "No @Mapper interfaces found. Ensure mapper interfaces are annotated with @Mapper."
                );
                return false;
            }

            MapperMethodScanner mapperScanner = new MapperMethodScanner(processingEnv);
            var scan = mapperScanner.scan(collectedMappers);

            // ✅ scan 결과 로그 추가 (핵심)
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "MES scanned namespaces=" + scan.expected().keySet().size()
            );

            // 오버로딩 금지
            if (!scan.overloadedMethodNames().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Overloaded mapper methods are not supported (XML id collision).\n");
                for (var e : scan.overloadedMethodNames().entrySet()) {
                    for (String name : e.getValue()) {
                        sb.append("- ").append(e.getKey()).append("#").append(name).append("\n");
                    }
                }
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, sb.toString());
                return false;
            }

            var expected = scan.expected();
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "MES expected ids(PostMapper)=" + expected.getOrDefault(
                            "com.example.libaray_test.mapper.PostMapper",
                            java.util.Collections.emptySet()
                    )
            );
            XmlMapperScanner xmlScanner = new XmlMapperScanner(options);
            var xmlIndex = xmlScanner.scan();
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "MES xml for PostMapper=" + xmlIndex.xmlPathOf(
                            "com.example.libaray_test.mapper.PostMapper"
                    ).orElse(null)
            );
            DiffResult diff = MapperXmlValidator.diff(expected, xmlIndex);

            // ✅ diff 결과 로그 추가 (핵심)
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "MES diff: missingNamespaces=" + diff.missing().size()
                            + ", orphanNamespaces=" + diff.orphan().size()
            );

            if (!diff.missing().isEmpty()) {
                String msg = diff.formatMissing(50);
                processingEnv.getMessager().printMessage(
                        options.failOnMissing() ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING,
                        msg
                );
            }

            if (!diff.orphan().isEmpty()) {
                String msg = diff.formatOrphan(50);
                processingEnv.getMessager().printMessage(
                        options.failOnOrphan() ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING,
                        msg
                );
            }

            boolean hasWork = !diff.missing().isEmpty() || !diff.orphan().isEmpty();

            if (options.generateMissing() && hasWork) {
                new XmlStubGenerator(options).generateMissingStubs(diff, xmlIndex);
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "MES: updated XML stubs/orphans.");
            }

        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "mes-processor failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }

        return false;
    }

}
