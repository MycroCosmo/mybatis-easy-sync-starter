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
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.LinkedHashSet;
import java.util.Set;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions({
        ProcessorOptions.KEY_XML_DIR,
        ProcessorOptions.KEY_FAIL_ON_MISSING,
        ProcessorOptions.KEY_FAIL_ON_ORPHAN,
        ProcessorOptions.KEY_GENERATE_MISSING,
        ProcessorOptions.KEY_DEBUG
})
public final class MesProcessor extends AbstractProcessor {

    // 라운드별 누적 (마지막 라운드에서만 실행)
    private final Set<TypeElement> collectedMappers = new LinkedHashSet<>();

    private TypeElement mapperAnn;
    private Messager messager;

    private ProcessorOptions options;
    private boolean debug;

    // 재사용
    private MapperMethodScanner mapperScanner;
    private XmlMapperScanner xmlScanner;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("org.apache.ibatis.annotations.Mapper");
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        this.messager = processingEnv.getMessager();
        this.options = ProcessorOptions.from(processingEnv.getOptions());
        this.debug = options.debug();

        this.mapperAnn = processingEnv.getElementUtils()
                .getTypeElement("org.apache.ibatis.annotations.Mapper");

        // ctor에 env 없음
        this.mapperScanner = new MapperMethodScanner();
        this.xmlScanner = new XmlMapperScanner(options);

        if (debug) {
            note("MES init options=" + processingEnv.getOptions());
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mapperAnn == null) {
            error("mybatis @Mapper annotation not found on classpath.");
            return false;
        }

        // 1) 수집: @Mapper 붙은 것 중 interface만 누적
        var newly = roundEnv.getElementsAnnotatedWith(mapperAnn);
        int newlyCollected = 0;

        for (Element el : newly) {
            if (el.getKind() == ElementKind.INTERFACE) {
                collectedMappers.add((TypeElement) el);
                newlyCollected++;
            } else if (debug) {
                note("MES ignored non-interface @Mapper element: " + el);
            }
        }

        if (debug) {
            note("MES round: processingOver=" + roundEnv.processingOver()
                    + ", annotations=" + annotations.size()
                    + ", newlyFound=" + newly.size()
                    + ", newlyCollectedInterfaces=" + newlyCollected
                    + ", collectedTotal=" + collectedMappers.size());
        }

        // 2) 마지막 라운드에서만 실행
        if (!roundEnv.processingOver()) {
            return false;
        }

        if (debug) note("MES last round start");

        try {
            if (collectedMappers.isEmpty()) {
                if (debug) note("MES: no @Mapper interfaces found.");
                return true;
            }

            var scan = mapperScanner.scan(collectedMappers);

            if (debug) {
                note("MES scanned namespaces=" + scan.expected().size());
            }

            // 오버로딩 금지
            if (!scan.overloadedMethodNames().isEmpty()) {
                StringBuilder sb = new StringBuilder(256);
                sb.append("Overloaded mapper methods are not supported (XML id collision).\n");
                for (var e : scan.overloadedMethodNames().entrySet()) {
                    for (String name : e.getValue()) {
                        sb.append("- ").append(e.getKey()).append("#").append(name).append("\n");
                    }
                }
                error(sb.toString());
                return true;
            }

            var expected = scan.expected();
            var xmlIndex = xmlScanner.scan();

            DiffResult diff = MapperXmlValidator.diff(expected, xmlIndex);

            if (debug) {
                note("MES diff: missingNamespaces=" + diff.missing().size()
                        + ", orphanNamespaces=" + diff.orphan().size()
                        + ", xmlRoot=" + xmlIndex.resolvedRoot());
            }

            if (!diff.missing().isEmpty()) {
                String msg = debug ? diff.formatMissingDetailed(50) : diff.formatMissing(5);
                print(options.failOnMissing() ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING, msg);
            }

            // orphan도 missing과 동일한 로그 정책
            if (!diff.orphan().isEmpty()) {
                String msg = debug ? diff.formatOrphanDetailed(50) : diff.formatOrphan(5);
                print(options.failOnOrphan() ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING, msg);
            }

            boolean hasWork = !diff.missing().isEmpty() || !diff.orphan().isEmpty();

            if (options.generateMissing() && hasWork) {
                new XmlStubGenerator().generateMissingStubs(diff, xmlIndex);

                //  NOTE는 debug에서만
                if (debug) {
                    note("MES: updated XML stubs/orphans.");
                }
            }

        } catch (Exception e) {
            error(buildFailureMessage(e));
        }

        return true;
    }

    private String buildFailureMessage(Exception e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("mes-processor failed: ")
          .append(e.getClass().getSimpleName())
          .append(": ")
          .append(safeMsg(e));

        if (debug) {
            Throwable c = e.getCause();
            int depth = 0;
            while (c != null && depth++ < 5) {
                sb.append("\n  caused by: ")
                  .append(c.getClass().getSimpleName())
                  .append(": ")
                  .append(safeMsg(c));
                c = c.getCause();
            }
        }

        return sb.toString();
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? "(no message)" : m;
    }

    private void note(String msg) {
        print(Diagnostic.Kind.NOTE, msg);
    }

    private void error(String msg) {
        print(Diagnostic.Kind.ERROR, msg);
    }

    private void print(Diagnostic.Kind kind, String msg) {
        if (messager != null) {
            messager.printMessage(kind, msg);
        }
    }
}
